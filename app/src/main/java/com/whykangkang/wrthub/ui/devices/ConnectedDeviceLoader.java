package com.whykangkang.wrthub.ui.devices;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.model.ConnectedDevice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 已连接设备列表聚合:
 *  1. DHCP 租约作基表(IP/MAC/主机名/租约剩余时间)—— **只有租约能决定列表里有哪些设备**
 *  2. hostHints 补主机名与 IP
 *  3. 遍历无线接口的 assoclist,把信号 dBm / SSID / 收发字节关联到对应 MAC
 *  4. 用本机 IP 标记「本机」徽章
 *
 * 第 2、3 步只做「补充信息」,不新增设备 —— 与 iOS fetchConnectedDevicesWithSSID 一致。
 * hostHints 是路由器的整张已知主机表(含早就离线的、只有 IPv6 的记录),
 * 拿它建条目会让列表里冒出一堆根本没连着的设备。静态 IP 的设备交给「深度扫描」。
 */
public class ConnectedDeviceLoader {

    public interface Listener {
        void onData(List<ConnectedDevice> devices);
    }

    private final OpenWrtApi api;
    private final String localIp;

    public ConnectedDeviceLoader(OpenWrtApi api, String localIp) {
        this.api = api;
        this.localIp = localIp;
    }

    public void load(Listener listener) {
        // MAC(小写) -> device
        final Map<String, ConnectedDevice> byMac = new LinkedHashMap<>();

        api.getDHCPLeases(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                parseLeases(result, byMac);
                loadHostHints(byMac, listener);
            }

            @Override
            public void onFailure(ApiError error) {
                loadHostHints(byMac, listener);
            }
        });
    }

    private void loadHostHints(Map<String, ConnectedDevice> byMac, Listener listener) {
        api.getHostHints(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                mergeHostHints(result, byMac);
                loadWifi(byMac, listener);
            }

            @Override
            public void onFailure(ApiError error) {
                loadWifi(byMac, listener);
            }
        });
    }

    private void loadWifi(Map<String, ConnectedDevice> byMac, Listener listener) {
        api.getWirelessDevices(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject wireless) {
                // 收集 (ifname, ssid)
                List<String[]> ifaces = new ArrayList<>();
                for (String radio : wireless.keySet()) {
                    if (!wireless.get(radio).isJsonObject()) continue;
                    JsonObject r = wireless.getAsJsonObject(radio);
                    if (!r.has("interfaces") || !r.get("interfaces").isJsonArray()) continue;
                    for (JsonElement ie : r.getAsJsonArray("interfaces")) {
                        JsonObject iface = ie.getAsJsonObject();
                        String ifname = iface.has("ifname") ? iface.get("ifname").getAsString() : null;
                        String ssid = null;
                        if (iface.has("config") && iface.get("config").isJsonObject()) {
                            JsonObject cfg = iface.getAsJsonObject("config");
                            if (cfg.has("ssid")) ssid = cfg.get("ssid").getAsString();
                        }
                        if (ifname != null) {
                            ifaces.add(new String[]{ifname, ssid});
                        }
                    }
                }
                fetchAssoclists(ifaces, byMac, listener);
            }

            @Override
            public void onFailure(ApiError error) {
                finish(byMac, listener);
            }
        });
    }

    private void fetchAssoclists(List<String[]> ifaces,
                                 Map<String, ConnectedDevice> byMac, Listener listener) {
        if (ifaces.isEmpty()) {
            finish(byMac, listener);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(ifaces.size());
        for (String[] pair : ifaces) {
            String ifname = pair[0];
            String ssid = pair[1];
            api.getWifiAssoclist(ifname, new ApiCallback<JsonObject>() {
                @Override
                public void onSuccess(JsonObject result) {
                    mergeAssoclist(result, ssid, byMac);
                    if (remaining.decrementAndGet() == 0) finish(byMac, listener);
                }

                @Override
                public void onFailure(ApiError error) {
                    if (remaining.decrementAndGet() == 0) finish(byMac, listener);
                }
            });
        }
    }

    private void finish(Map<String, ConnectedDevice> byMac, Listener listener) {
        List<ConnectedDevice> list = new ArrayList<>(byMac.values());
        listener.onData(list);
    }

    // ---- 解析 ----

    void parseLeases(JsonObject result, Map<String, ConnectedDevice> byMac) {
        if (!result.has("dhcp_leases") || !result.get("dhcp_leases").isJsonArray()) return;
        for (JsonElement e : result.getAsJsonArray("dhcp_leases")) {
            JsonObject lease = e.getAsJsonObject();
            ConnectedDevice d = new ConnectedDevice();
            d.macAddress = optStr(lease, "macaddr");
            d.ipAddress = optStr(lease, "ipaddr");
            d.hostname = optStr(lease, "hostname");
            if (lease.has("expires")) {
                try {
                    // expires = 剩余秒数;负数(无限租约/已释放)按 0 处理
                    d.leaseRemainingSec = Math.max(0, lease.get("expires").getAsLong());
                } catch (Exception ignored) {
                }
            }
            if (d.ipAddress != null && d.ipAddress.equals(localIp)) {
                d.isLocal = true;
            }
            if (d.macAddress != null) {
                byMac.put(d.macAddress.toLowerCase(Locale.ROOT), d);
            }
        }
    }

    void mergeHostHints(JsonObject hints, Map<String, ConnectedDevice> byMac) {
        for (String mac : hints.keySet()) {
            if (!hints.get(mac).isJsonObject()) continue;
            JsonObject hint = hints.getAsJsonObject(mac);
            String key = mac.toLowerCase(Locale.ROOT);
            ConnectedDevice d = byMac.get(key);
            if (d == null) continue;          // 没有租约就不是「已连接」,不建条目
            if ((d.hostname == null || d.hostname.isEmpty()) && hint.has("name")) {
                d.hostname = hint.get("name").getAsString();
            }
            // 租约里没带 IP 时用 hostHints 补一个
            if ((d.ipAddress == null || d.ipAddress.isEmpty())
                    && hint.has("ipaddrs") && hint.get("ipaddrs").isJsonArray()) {
                JsonArray ips = hint.getAsJsonArray("ipaddrs");
                if (ips.size() > 0) {
                    d.ipAddress = ips.get(0).getAsString();
                    if (d.ipAddress.equals(localIp)) d.isLocal = true;
                }
            }
        }
    }

    void mergeAssoclist(JsonObject result, String ssid, Map<String, ConnectedDevice> byMac) {
        JsonObject results = result;
        // 兼容 {"results":[...]} 或直接 {mac:{...}}
        if (result.has("results") && result.get("results").isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray("results")) {
                JsonObject sta = e.getAsJsonObject();
                applySta(sta.has("mac") ? sta.get("mac").getAsString() : null, sta, ssid, byMac);
            }
        } else {
            for (String mac : results.keySet()) {
                if (results.get(mac).isJsonObject()) {
                    applySta(mac, results.getAsJsonObject(mac), ssid, byMac);
                }
            }
        }
    }

    private void applySta(String mac, JsonObject sta, String ssid, Map<String, ConnectedDevice> byMac) {
        if (mac == null) return;
        String key = mac.toLowerCase(Locale.ROOT);
        ConnectedDevice d = byMac.get(key);
        // 关联上了但没拿到租约的客户端也不单列,只把信息贴到已有条目上
        if (d == null) return;
        if (sta.has("signal")) {
            try {
                d.signalDbm = sta.get("signal").getAsInt();
            } catch (Exception ignored) {
            }
        }
        d.ssid = ssid;
        if (sta.has("rx") && sta.get("rx").isJsonObject()) {
            d.rxBytes = optLong(sta.getAsJsonObject("rx"), "bytes");
        }
        if (sta.has("tx") && sta.get("tx").isJsonObject()) {
            d.txBytes = optLong(sta.getAsJsonObject("tx"), "bytes");
        }
    }

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long optLong(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}