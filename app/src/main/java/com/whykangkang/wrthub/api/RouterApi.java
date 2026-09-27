package com.whykangkang.wrthub.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.model.ConnectionTracking;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.NetworkInterfaceInfo;
import com.whykangkang.wrthub.model.RouterInfo;
import com.whykangkang.wrthub.model.StorageInfo;
import com.whykangkang.wrthub.model.WiFiInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首页/实时页用的聚合查询,对应 iOS OpenWrtAPI 的
 * getSystemInfo / getNetworkDevices / getNetworkInterfaces /
 * getStorageInfo / getConnectionTracking / getWiFiInfo 六个分区。
 *
 * 解析逻辑与 iOS 逐行对照,含那些踩过坑的兜底(CPU 温度先看 tempinfo、
 * getWirelessDevices 为空时回退 uci、存储去重与排序等)。
 */
public final class RouterApi {

    private RouterApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    // =====================================================================
    // 系统信息
    // =====================================================================

    /**
     * system.info + system.board + luci.getCPUInfo/getCPUUsage/getTempInfo 合并。
     * 后三个属 OPTIONAL_ACL 白名单,无权限时静默跳过(不触发重登)。
     */
    public static void getSystemInfo(ApiCallback<RouterInfo> cb) {
        JsonObject merged = new JsonObject();
        api().getSystemInfo(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject info) {
                mergeInto(merged, info);
                api().getSystemBoard(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject board) {
                        mergeInto(merged, board);
                        loadCpu();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        loadCpu();
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }

            private void loadCpu() {
                api().getCpuInfo(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject cpu) {
                        mergeInto(merged, cpu);
                        loadUsage();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        loadUsage();
                    }
                });
            }

            private void loadUsage() {
                api().getCpuUsage(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject usage) {
                        mergeInto(merged, usage);
                        loadTemp();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        loadTemp();
                    }
                });
            }

            private void loadTemp() {
                api().getTempInfo(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject temp) {
                        mergeInto(merged, temp);
                        cb.onSuccess(parseSystemInfo(merged));
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onSuccess(parseSystemInfo(merged));
                    }
                });
            }
        });
    }

    private static void mergeInto(JsonObject target, JsonObject source) {
        if (source == null) return;
        for (String key : source.keySet()) {
            target.add(key, source.get(key));
        }
    }

    private static final Pattern CORE_PATTERN = Pattern.compile("(\\d+)C");
    private static final Pattern CPU_TEMP_PATTERN =
            Pattern.compile("CPU:\\s*([0-9.]+)°C");
    private static final Pattern ANY_TEMP_PATTERN = Pattern.compile("([0-9.]+)°C");
    private static final Pattern FREQ_PATTERN = Pattern.compile("([0-9.]+)MHz");

    /** 可单测:合并后的 ubus 数据 → RouterInfo */
    public static RouterInfo parseSystemInfo(JsonObject data) {
        RouterInfo info = new RouterInfo();
        info.hostname = str(data, "hostname", "OpenWrt");
        info.uptime = num(data, "uptime", 0);
        info.system = strOrNull(data, "system");
        info.model = strOrNull(data, "model");

        JsonElement releaseEl = data.get("release");
        if (releaseEl != null && releaseEl.isJsonObject()) {
            JsonObject release = releaseEl.getAsJsonObject();
            info.release = strOrNull(release, "description");
            if (info.kernel == null) info.kernel = strOrNull(release, "kernel");
        }
        String kernel = strOrNull(data, "kernel");
        if (kernel != null) info.kernel = kernel;

        // load 是 1/65536 定点数
        JsonElement loadEl = data.get("load");
        if (loadEl != null && loadEl.isJsonArray()) {
            JsonArray load = loadEl.getAsJsonArray();
            for (int i = 0; i < 3 && i < load.size(); i++) {
                info.loadavg[i] = load.get(i).getAsDouble() / 65536.0;
            }
        }

        JsonElement memEl = data.get("memory");
        if (memEl != null && memEl.isJsonObject()) {
            JsonObject mem = memEl.getAsJsonObject();
            info.memory.total = num(mem, "total", 0);
            info.memory.free = num(mem, "free", 0);
            info.memory.buffered = num(mem, "buffered", 0);
            info.memory.shared = num(mem, "shared", 0);
            long cached = num(mem, "cached", 0);
            info.memory.cached = cached > 0 ? cached : info.memory.shared;
            if (mem.has("available") && !mem.get("available").isJsonNull()) {
                info.memory.available = mem.get("available").getAsLong();
            }
        }

        // luci.getCPUInfo 形如 "Intel(R) N100 x 4C 4T (973.352MHz, 27.0°C)"
        String cpuinfo = strOrNull(data, "cpuinfo");
        if (cpuinfo != null) {
            info.system = cpuinfo;
            RouterInfo.CpuInfo cpu = new RouterInfo.CpuInfo();
            String usageStr = strOrNull(data, "cpuusage");
            if (usageStr != null) {
                try {
                    cpu.usage = Double.parseDouble(usageStr.replace("%", "").trim());
                } catch (NumberFormatException ignored) {
                    cpu.usage = 0;
                }
            }
            Matcher cores = CORE_PATTERN.matcher(cpuinfo);
            if (cores.find()) {
                cpu.count = Integer.parseInt(cores.group(1));
            }
            // 温度优先取 tempinfo("CPU: 46.4°C, WiFi: 37.0°C"),没有再从 cpuinfo 里挖
            String tempinfo = strOrNull(data, "tempinfo");
            if (tempinfo != null) {
                Matcher m = CPU_TEMP_PATTERN.matcher(tempinfo);
                if (m.find()) cpu.temperature = Double.parseDouble(m.group(1));
            }
            if (cpu.temperature == null) {
                Matcher m = ANY_TEMP_PATTERN.matcher(cpuinfo);
                if (m.find()) cpu.temperature = Double.parseDouble(m.group(1));
            }
            Matcher freq = FREQ_PATTERN.matcher(cpuinfo);
            if (freq.find()) {
                cpu.frequency = (int) Double.parseDouble(freq.group(1));
            }
            info.cpu = cpu;
        }
        return info;
    }

    // =====================================================================
    // 网络设备
    // =====================================================================

    public static void getNetworkDevices(ApiCallback<List<NetworkDevice>> cb) {
        api().getNetworkDevices(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseNetworkDevices(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static List<NetworkDevice> parseNetworkDevices(JsonObject data) {
        List<NetworkDevice> devices = new ArrayList<>();
        for (String name : data.keySet()) {
            JsonElement el = data.get(name);
            if (!el.isJsonObject()) continue;
            JsonObject d = el.getAsJsonObject();
            NetworkDevice dev = new NetworkDevice();
            dev.name = name;
            dev.up = bool(d, "up", false);
            dev.wireless = bool(d, "wireless", false);
            if (d.has("mtu") && d.get("mtu").isJsonPrimitive()) {
                dev.mtu = d.get("mtu").getAsInt();
            }
            dev.mac = strOrNull(d, "mac");
            dev.master = strOrNull(d, "master");

            JsonElement ips = d.get("ipaddrs");
            if (ips != null && ips.isJsonArray()) {
                for (JsonElement ipEl : ips.getAsJsonArray()) {
                    if (!ipEl.isJsonObject()) continue;
                    JsonObject ip = ipEl.getAsJsonObject();
                    String address = strOrNull(ip, "address");
                    if (address == null) continue;
                    NetworkDevice.IpAddress addr = new NetworkDevice.IpAddress();
                    addr.address = address;
                    addr.netmask = strOrNull(ip, "netmask");
                    addr.broadcast = strOrNull(ip, "broadcast");
                    dev.ipaddrs.add(addr);
                }
            }

            JsonElement statsEl = d.get("stats");
            if (statsEl != null && statsEl.isJsonObject()) {
                JsonObject s = statsEl.getAsJsonObject();
                dev.stats.rxBytes = num(s, "rx_bytes", 0);
                dev.stats.txBytes = num(s, "tx_bytes", 0);
                dev.stats.rxPackets = num(s, "rx_packets", 0);
                dev.stats.txPackets = num(s, "tx_packets", 0);
            }

            JsonElement linkEl = d.get("link");
            if (linkEl != null && linkEl.isJsonObject()) {
                JsonObject l = linkEl.getAsJsonObject();
                if (l.has("speed") && l.get("speed").isJsonPrimitive()) {
                    try {
                        dev.link.speed = l.get("speed").getAsInt();
                    } catch (NumberFormatException ignored) {
                        dev.link.speed = null;
                    }
                }
                dev.link.duplex = strOrNull(l, "duplex");
                dev.link.carrier = bool(l, "carrier", false);
            }
            devices.add(dev);
        }
        // 物理口在前,再网桥,最后按名字(与 iOS 排序一致)
        Collections.sort(devices, (a, b) -> {
            if (a.isPhysicalPort() != b.isPhysicalPort()) return a.isPhysicalPort() ? -1 : 1;
            if (a.isBridge() != b.isBridge()) return a.isBridge() ? -1 : 1;
            return a.name.compareTo(b.name);
        });
        return devices;
    }

    // =====================================================================
    // 逻辑接口
    // =====================================================================

    /** network.interface.dump + getNetworkDevices 的收发统计,按设备名关联 */
    public static void getNetworkInterfaces(ApiCallback<List<NetworkInterfaceInfo>> cb) {
        api().getNetworkInterfaceDump(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject dump) {
                api().getNetworkDevices(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject devices) {
                        cb.onSuccess(parseInterfaces(dump, devices));
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onSuccess(parseInterfaces(dump, new JsonObject()));
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static List<NetworkInterfaceInfo> parseInterfaces(JsonObject dump, JsonObject devices) {
        Map<String, long[]> stats = new HashMap<>();
        for (String name : devices.keySet()) {
            JsonElement el = devices.get(name);
            if (!el.isJsonObject()) continue;
            JsonElement statsEl = el.getAsJsonObject().get("stats");
            if (statsEl == null || !statsEl.isJsonObject()) continue;
            JsonObject s = statsEl.getAsJsonObject();
            stats.put(name, new long[]{num(s, "rx_bytes", 0), num(s, "tx_bytes", 0)});
        }

        List<NetworkInterfaceInfo> out = new ArrayList<>();
        JsonElement listEl = dump.get("interface");
        if (listEl == null || !listEl.isJsonArray()) return out;
        for (JsonElement el : listEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            NetworkInterfaceInfo iface = new NetworkInterfaceInfo();
            iface.name = str(o, "interface", "unknown");
            iface.device = str(o, "device", "");
            iface.proto = str(o, "proto", "");
            iface.up = bool(o, "up", false);
            if (o.has("uptime") && o.get("uptime").isJsonPrimitive()) {
                iface.uptime = o.get("uptime").getAsInt();
            }
            JsonElement v4 = o.get("ipv4-address");
            if (v4 != null && v4.isJsonArray() && v4.getAsJsonArray().size() > 0) {
                JsonElement first = v4.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    iface.ipv4 = strOrNull(first.getAsJsonObject(), "address");
                }
            }
            long[] s = stats.get(iface.device);
            if (s != null) {
                iface.rxBytes = s[0];
                iface.txBytes = s[1];
            }
            out.add(iface);
        }
        return out;
    }

    // =====================================================================
    // 存储
    // =====================================================================

    public static void getStorageInfo(ApiCallback<List<StorageInfo>> cb) {
        api().getMountPoints(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseStorage(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static List<StorageInfo> parseStorage(JsonObject data) {
        List<StorageInfo> list = new ArrayList<>();
        JsonElement resultEl = data.get("result");
        if (resultEl == null || !resultEl.isJsonArray()) return list;
        Set<String> seen = new HashSet<>();
        for (JsonElement el : resultEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            StorageInfo s = new StorageInfo();
            s.device = str(o, "device", "");
            s.mount = str(o, "mount", "");
            s.size = num(o, "size", 0);
            s.avail = num(o, "avail", 0);
            s.free = num(o, "free", 0);
            if (s.mount.isEmpty()) continue;
            if (s.isImportant() && seen.add(s.mount)) {
                list.add(s);
            }
        }
        List<String> order = Arrays.asList("/rom", "/overlay", "/boot", "/tmp");
        Collections.sort(list, (a, b) -> {
            int pa = order.indexOf(a.mount);
            int pb = order.indexOf(b.mount);
            if (pa < 0) pa = 99;
            if (pb < 0) pb = 99;
            return Integer.compare(pa, pb);
        });
        return list;
    }

    // =====================================================================
    // 连接跟踪
    // =====================================================================

    public static void getConnectionTracking(ApiCallback<ConnectionTracking> cb) {
        api().fileRead("/proc/sys/net/netfilter/nf_conntrack_count", new ApiCallback<String>() {
            @Override
            public void onSuccess(String countText) {
                int current = parseInt(countText);
                api().fileRead("/proc/sys/net/netfilter/nf_conntrack_max",
                        new ApiCallback<String>() {
                            @Override
                            public void onSuccess(String maxText) {
                                cb.onSuccess(new ConnectionTracking(current, parseInt(maxText)));
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                cb.onFailure(error);
                            }
                        });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    private static int parseInt(String text) {
        if (text == null) return 0;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =====================================================================
    // WiFi
    // =====================================================================

    /**
     * uci wireless(找出被禁用的接口)+ luci-rpc.getWirelessDevices(活动接口),
     * 再对每个 ifname 调 iwinfo.assoclist 数客户端。
     * x86 等无无线的固件上 getWirelessDevices 返回空,直接给空列表(iOS 修过的坑)。
     */
    public static void getWiFiInfo(ApiCallback<List<WiFiInfo>> cb) {
        api().uciGetConfig("wireless", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject uci) {
                loadDevices(collectDisabled(uci));
            }

            @Override
            public void onFailure(ApiError error) {
                loadDevices(new HashMap<>());
            }

            private void loadDevices(Map<String, JsonObject> disabled) {
                api().getWirelessDevices(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject data) {
                        List<WiFiInfo> list = new ArrayList<>();
                        Map<String, String> ifnameMap = new HashMap<>();
                        parseWirelessDevices(data, disabled, list, ifnameMap);
                        if (list.isEmpty()) {
                            cb.onSuccess(list);
                        } else {
                            updateClientCounts(list, ifnameMap, cb);
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onSuccess(new ArrayList<>());
                    }
                });
            }
        });
    }

    static Map<String, JsonObject> collectDisabled(JsonObject uci) {
        Map<String, JsonObject> disabled = new HashMap<>();
        JsonObject values = uci.has("values") && uci.get("values").isJsonObject()
                ? uci.getAsJsonObject("values") : uci;
        for (String section : values.keySet()) {
            JsonElement el = values.get(section);
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!"wifi-iface".equals(str(o, ".type", ""))) continue;
            if ("1".equals(str(o, "disabled", "0"))) {
                disabled.put(section, o);
            }
        }
        return disabled;
    }

    static void parseWirelessDevices(JsonObject data, Map<String, JsonObject> disabled,
                                     List<WiFiInfo> out, Map<String, String> ifnameMap) {
        for (String radioId : data.keySet()) {
            JsonElement radioEl = data.get(radioId);
            if (!radioEl.isJsonObject()) continue;
            JsonObject radio = radioEl.getAsJsonObject();
            boolean radioDisabled = bool(radio, "disabled", false);
            boolean radioUp = bool(radio, "up", true);
            JsonElement ifacesEl = radio.get("interfaces");
            if (ifacesEl == null || !ifacesEl.isJsonArray()) continue;
            JsonArray ifaces = ifacesEl.getAsJsonArray();
            for (int i = 0; i < ifaces.size(); i++) {
                if (!ifaces.get(i).isJsonObject()) continue;
                JsonObject iface = ifaces.get(i).getAsJsonObject();
                JsonObject config = iface.has("config") && iface.get("config").isJsonObject()
                        ? iface.getAsJsonObject("config") : null;
                JsonObject iwinfo = iface.has("iwinfo") && iface.get("iwinfo").isJsonObject()
                        ? iface.getAsJsonObject("iwinfo") : null;

                String ssid = config != null ? str(config, "ssid", null) : null;
                if (ssid == null && iwinfo != null) ssid = str(iwinfo, "ssid", null);
                String section = str(iface, "section", radioId + "_" + i);
                if (ssid == null) ssid = section;

                int channel = 0;
                if (iwinfo != null) channel = (int) num(iwinfo, "channel", 0);
                if (channel == 0 && config != null) {
                    try {
                        channel = Integer.parseInt(str(config, "channel", "0"));
                    } catch (NumberFormatException ignored) {
                        channel = 0;
                    }
                }

                WiFiInfo wifi = new WiFiInfo();
                wifi.device = radioId;
                wifi.channel = channel;
                wifi.ssid = ssid;
                wifi.encryptionRaw = config != null ? str(config, "encryption", "none") : "none";
                wifi.encryption = encryptionLabel(wifi.encryptionRaw);
                wifi.uciDisabled = config != null && "1".equals(str(config, "disabled", "0"));
                wifi.mode = channel > 14 ? "5GHz" : "2.4GHz";
                wifi.id = section;
                wifi.workMode = config != null ? strOrNull(config, "mode") : null;
                wifi.password = config != null ? strOrNull(config, "key") : null;
                // 客户端模式没有 iwinfo 说明没连上,按未启用显示(与 iOS 一致)
                wifi.enabled = !radioDisabled && radioUp
                        && !("sta".equals(wifi.workMode) && iwinfo == null);
                out.add(wifi);

                String ifname = str(iface, "ifname", "");
                if (!ifname.isEmpty()) ifnameMap.put(section, ifname);
            }
        }
        // 再补上 uci 里被禁用的接口(它们不在 getWirelessDevices 结果里)
        for (Map.Entry<String, JsonObject> e : disabled.entrySet()) {
            JsonObject config = e.getValue();
            WiFiInfo wifi = new WiFiInfo();
            wifi.id = e.getKey();
            wifi.ssid = str(config, "ssid", e.getKey());
            wifi.device = str(config, "device", "radio0");
            wifi.workMode = strOrNull(config, "mode");
            wifi.password = strOrNull(config, "key");
            wifi.encryptionRaw = str(config, "encryption", "none");
            wifi.encryption = encryptionLabel(wifi.encryptionRaw);
            wifi.uciDisabled = true;   // 这批就是从 uci 的 disabled=1 里捞出来的
            wifi.channel = wifi.device.contains("1") ? 36 : 11;
            wifi.mode = wifi.channel > 14 ? "5GHz" : "2.4GHz";
            wifi.enabled = false;
            out.add(wifi);
        }
    }

    /** uci encryption 值 → 展示名(与 iOS 的映射一致) */
    static String encryptionLabel(String raw) {
        if (raw == null) return "OPEN";
        switch (raw) {
            case "none":
                return "OPEN";
            case "psk":
            case "psk-mixed":
                return "WPA/WPA2-PSK";
            case "psk2":
            case "psk2+ccmp":
                return "WPA2-PSK";
            case "psk2+aes":
                return "WPA2-PSK (AES)";
            case "sae":
            case "sae-mixed":
                return "WPA3-SAE";
            default:
                return raw.toUpperCase(Locale.ROOT);
        }
    }

    private static void updateClientCounts(List<WiFiInfo> list, Map<String, String> ifnameMap,
                                           ApiCallback<List<WiFiInfo>> cb) {
        AtomicInteger remaining = new AtomicInteger(list.size());
        Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) cb.onSuccess(list);
        };
        for (WiFiInfo wifi : list) {
            String ifname = ifnameMap.get(wifi.id);
            if (ifname == null) {
                done.run();
                continue;
            }
            api().getWifiAssoclist(ifname, new ApiCallback<JsonObject>() {
                @Override
                public void onSuccess(JsonObject result) {
                    JsonElement results = result.get("results");
                    if (results != null && results.isJsonArray()) {
                        wifi.clients = results.getAsJsonArray().size();
                    }
                    done.run();
                }

                @Override
                public void onFailure(ApiError error) {
                    done.run();
                }
            });
        }
    }

    // =====================================================================
    // JSON 工具
    // =====================================================================

    static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    static String strOrNull(JsonObject o, String key) {
        return str(o, key, null);
    }

    static long num(JsonObject o, String key, long def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }
}
