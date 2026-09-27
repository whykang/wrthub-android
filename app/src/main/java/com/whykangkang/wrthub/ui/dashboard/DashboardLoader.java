package com.whykangkang.wrthub.ui.dashboard;

import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.RouterApi;
import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.model.ConnectionTracking;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.NetworkInterfaceInfo;
import com.whykangkang.wrthub.model.RouterInfo;
import com.whykangkang.wrthub.model.StorageInfo;
import com.whykangkang.wrthub.model.WiFiInfo;
import com.whykangkang.wrthub.ui.devices.ConnectedDeviceLoader;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首页数据聚合加载器,对应 iOS DashboardViewController.performLoadData 的 DispatchGroup。
 * 并发拉取 7 个数据源,全部返回(成功或失败)后回调一次。
 * 网速靠 WAN 字节差分,故 loader 在多次刷新间保持采样状态。
 */
public class DashboardLoader {

    public interface Listener {
        void onData(DashboardData data);
    }

    private final OpenWrtApi api;
    private final String localIp;

    // 网速差分状态
    private long lastRx = -1;
    private long lastTx = -1;
    private long lastSampleTime = -1;

    public DashboardLoader(OpenWrtApi api, String localIp) {
        this.api = api;
        this.localIp = localIp;
    }

    public void load(Listener listener) {
        final DashboardData data = new DashboardData();
        final AtomicInteger remaining = new AtomicInteger(7);
        final Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) {
                // 网速须在回调前算好(卡片重建时直接取值)
                computeWanSpeed(data);
                listener.onData(data);
            }
        };

        RouterApi.getSystemInfo(new ApiCallback<RouterInfo>() {
            @Override
            public void onSuccess(RouterInfo info) {
                data.routerInfo = info;
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                data.hadError = true;
                done.run();
            }
        });

        RouterApi.getNetworkInterfaces(new ApiCallback<List<NetworkInterfaceInfo>>() {
            @Override
            public void onSuccess(List<NetworkInterfaceInfo> result) {
                data.interfaces.addAll(result);
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });

        RouterApi.getNetworkDevices(new ApiCallback<List<NetworkDevice>>() {
            @Override
            public void onSuccess(List<NetworkDevice> result) {
                data.networkDevices.addAll(result);
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });

        new ConnectedDeviceLoader(api, localIp).load(devices -> {
            data.connectedDevices.addAll(devices);
            done.run();
        });

        RouterApi.getWiFiInfo(new ApiCallback<List<WiFiInfo>>() {
            @Override
            public void onSuccess(List<WiFiInfo> result) {
                data.wifi.addAll(result);
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });

        RouterApi.getStorageInfo(new ApiCallback<List<StorageInfo>>() {
            @Override
            public void onSuccess(List<StorageInfo> result) {
                data.storage.addAll(result);
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });

        RouterApi.getConnectionTracking(new ApiCallback<ConnectionTracking>() {
            @Override
            public void onSuccess(ConnectionTracking result) {
                data.conntrack = result;
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });
    }

    /**
     * 选出代表外网的接口设备:优先 wan,其次 wwan*,再退回任一非 lan 的在线接口,
     * 用两次刷新间的累计字节差分算速率(与 iOS updateWanSpeed 一致)。
     */
    private void computeWanSpeed(DashboardData data) {
        String dev = wanDeviceName(data);
        if (dev == null) return;
        NetworkDevice device = null;
        for (NetworkDevice d : data.networkDevices) {
            if (d.name.equals(dev)) {
                device = d;
                break;
            }
        }
        if (device == null) return;
        long now = System.currentTimeMillis();
        long rx = device.stats.rxBytes;
        long tx = device.stats.txBytes;
        if (lastSampleTime > 0) {
            double dt = (now - lastSampleTime) / 1000.0;
            // 计数器可能因接口重启回绕,负差按 0 处理
            if (dt > 0.5) {
                data.wanDownSpeed = Math.max(0, (rx - lastRx) / dt);
                data.wanUpSpeed = Math.max(0, (tx - lastTx) / dt);
            }
        }
        lastRx = rx;
        lastTx = tx;
        lastSampleTime = now;
    }

    static String wanDeviceName(DashboardData data) {
        NetworkInterfaceInfo pick = null;
        for (NetworkInterfaceInfo i : data.interfaces) {
            if ("wan".equals(i.name) && i.up) {
                pick = i;
                break;
            }
        }
        if (pick == null) {
            for (NetworkInterfaceInfo i : data.interfaces) {
                if (i.name.startsWith("wwan") && i.up) {
                    pick = i;
                    break;
                }
            }
        }
        if (pick == null) {
            for (NetworkInterfaceInfo i : data.interfaces) {
                if (i.up && !"lan".equals(i.name) && !"loopback".equals(i.name)) {
                    pick = i;
                    break;
                }
            }
        }
        return pick != null ? pick.device : null;
    }

    /** WAN IPv4(桌面小组件用) */
    public static String wanIp(DashboardData data) {
        for (NetworkInterfaceInfo i : data.interfaces) {
            if ("wan".equals(i.name) && i.up && i.ipv4 != null) return i.ipv4;
        }
        for (NetworkInterfaceInfo i : data.interfaces) {
            if (i.name.startsWith("wwan") && i.up && i.ipv4 != null) return i.ipv4;
        }
        return null;
    }
}
