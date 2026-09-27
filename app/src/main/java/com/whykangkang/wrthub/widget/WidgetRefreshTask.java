package com.whykangkang.wrthub.widget;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.RouterApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.model.RouterInfo;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 小组件自己去路由器取一轮数据,写回 {@link WidgetSnapshot}。
 *
 * 之前快照只有主 App 打开首页时才会写,App 不开小组件就一直停在旧数据上
 * (超过 15 分钟会变淡)。这里让小组件在自动周期和手动刷新时也能自己取数。
 *
 * **写回快照而不是自己渲染**,是为了保持单一数据源:
 * 展示哪些指标由「设置 → 小组件设置」决定,渲染逻辑只有
 * {@link WrtHubWidgetProvider} 一处。
 */
final class WidgetRefreshTask {

    /** 两次采样的间隔,差分算瞬时速率 */
    private static final long SAMPLE_GAP_MS = 1500;

    /**
     * 整轮硬上限。BroadcastReceiver 的 goAsync 大约 10 秒判 ANR,
     * 而路由器不可达时 OkHttp 会等到自己的超时,必须先收手。
     */
    private static final long DEADLINE_MS = 8000;

    private WidgetRefreshTask() {
    }

    /** 在后台线程同步跑完。成功写入快照返回 true。 */
    static boolean run(Context context) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        if (!api.isConfigured()) return false;

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        List<NetworkDevice> firstAll = fetchDevices(remaining(deadline));
        NetworkDevice first = wanDevice(firstAll);
        if (first == null) return false;

        try {
            Thread.sleep(SAMPLE_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        NetworkDevice second = wanDevice(fetchDevices(remaining(deadline)));
        Double down = null;
        Double up = null;
        if (second != null) {
            double seconds = SAMPLE_GAP_MS / 1000.0;
            down = Math.max(0, (second.stats.rxBytes - first.stats.rxBytes) / seconds);
            up = Math.max(0, (second.stats.txBytes - first.stats.txBytes) / seconds);
        }

        RouterInfo info = fetchSystemInfo(remaining(deadline));
        Integer devices = fetchClientCount(remaining(deadline));

        RouterDevice current = RouterDeviceManager.getInstance(context).getCurrentDevice();
        String name = current != null ? current.getDisplayName() : null;
        if (name == null || name.isEmpty()) {
            name = info != null && info.hostname != null ? info.hostname : "OpenWrt";
        }

        // 延迟这里不测(要额外一轮 ping,吃掉本就紧张的预算),
        // 把 App 上次测到的值原样带回去 —— 传 null 会把它从快照里抹掉。
        Float latency = WidgetSnapshot.latencyMs(context);
        boolean viaProxy = WidgetSnapshot.latencyViaProxy(context);

        WidgetSnapshot.write(context, name, down, up,
                latency != null ? latency.doubleValue() : null, viaProxy,
                devices,
                info != null && info.cpu != null ? info.cpu.usage : null,
                info != null ? info.memory.usedPercentage() : null,
                wanIp(second != null ? second : first));
        return true;
    }

    /** 与实时页同一套挑法:名字含 wan 的优先,否则第一块物理口 */
    static NetworkDevice wanDevice(List<NetworkDevice> devices) {
        if (devices == null) return null;
        for (NetworkDevice d : devices) {
            if (d.name != null && d.name.contains("wan")) return d;
        }
        for (NetworkDevice d : devices) {
            if (d.isPhysicalPort()) return d;
        }
        return devices.isEmpty() ? null : devices.get(0);
    }

    static String wanIp(NetworkDevice device) {
        if (device == null || device.ipaddrs.isEmpty()) return "";
        String address = device.ipaddrs.get(0).address;
        return address == null ? "" : address;
    }

    // =====================================================================

    private static long remaining(long deadline) {
        return Math.max(500, deadline - System.currentTimeMillis());
    }

    private static List<NetworkDevice> fetchDevices(long timeoutMs) {
        AtomicReference<List<NetworkDevice>> holder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        RouterApi.getNetworkDevices(new ApiCallback<List<NetworkDevice>>() {
            @Override
            public void onSuccess(List<NetworkDevice> devices) {
                holder.set(devices);
                latch.countDown();
            }

            @Override
            public void onFailure(ApiError error) {
                latch.countDown();
            }
        });
        await(latch, timeoutMs);
        return holder.get();
    }

    private static RouterInfo fetchSystemInfo(long timeoutMs) {
        AtomicReference<RouterInfo> holder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        RouterApi.getSystemInfo(new ApiCallback<RouterInfo>() {
            @Override
            public void onSuccess(RouterInfo info) {
                holder.set(info);
                latch.countDown();
            }

            @Override
            public void onFailure(ApiError error) {
                latch.countDown();
            }
        });
        await(latch, timeoutMs);
        return holder.get();
    }

    private static Integer fetchClientCount(long timeoutMs) {
        AtomicInteger count = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        OpenWrtApi.getInstance().getDHCPLeases(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonElement leases = result.get("dhcp_leases");
                if (leases != null && leases.isJsonArray()) {
                    count.set(leases.getAsJsonArray().size());
                }
                latch.countDown();
            }

            @Override
            public void onFailure(ApiError error) {
                latch.countDown();
            }
        });
        await(latch, timeoutMs);
        return count.get() >= 0 ? count.get() : null;
    }

    private static void await(CountDownLatch latch, long timeoutMs) {
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
