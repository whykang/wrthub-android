package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * arp-scan 深度扫描,对应 iOS OpenWrtAPI 的「ARP 深度扫描」分区。
 *
 * DHCP 租约只能看到「向路由器要过 IP」的设备:静态 IP、租约已过期的不会出现。
 * arp-scan 主动向整个网段广播 ARP 请求,把这些漏网设备扫出来。
 *
 * 两个前提,失败时要分开提示(用户的处理动作完全不同):
 *  1. 路由器上装了 arp-scan;
 *  2. rpcd 的 file ACL 授权执行它 —— 默认只放行 ping/traceroute/nslookup 等,
 *     哪怕装好了 file.exec 仍可能返回 6(权限被拒绝)。
 */
public final class ArpScanApi {

    /** 一条扫描结果 */
    public static final class Entry {
        public final String ip;
        /** 统一大写,便于与 ConnectedDevice 的 MAC 比对 */
        public final String mac;
        /** OUI 厂商名;arp-scan 未带 oui 库时为空 */
        public final String vendor;

        Entry(String ip, String mac, String vendor) {
            this.ip = ip;
            this.mac = mac;
            this.vendor = vendor;
        }
    }

    public enum Failure {
        /** 路由器上找不到 arp-scan 可执行文件 */
        NOT_INSTALLED,
        /** rpcd ACL 未授权 file.exec 执行它 */
        PERMISSION_DENIED,
        OTHER
    }

    public interface Callback {
        void onSuccess(List<Entry> entries);

        void onFailure(Failure failure, String message);
    }

    /** arp-scan 在不同固件/架构下的安装位置 */
    private static final List<String> PATHS = Arrays.asList(
            "/usr/bin/arp-scan", "/usr/sbin/arp-scan", "/bin/arp-scan", "/sbin/arp-scan");

    /**
     * 授权 rpcd 执行 arp-scan 的一段 shell(用户在路由器 SSH 里粘贴执行)。
     * rpcd 按「完整命令行」匹配 ACL,所以规则要带尾部通配符。
     */
    public static final String ACL_COMMAND =
            "cat > /usr/share/rpcd/acl.d/wrthub-arpscan.json <<'EOF'\n"
                    + "{\n"
                    + "  \"wrthub-arpscan\": {\n"
                    + "    \"description\": \"Allow WrtHub to run arp-scan\",\n"
                    + "    \"read\": {\n"
                    + "      \"file\": {\n"
                    + "        \"/usr/bin/arp-scan *\": [ \"exec\" ],\n"
                    + "        \"/usr/sbin/arp-scan *\": [ \"exec\" ]\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n"
                    + "EOF\n"
                    + "/etc/init.d/rpcd restart";

    private ArpScanApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    /** 完整流程:定位可执行文件 → 取 LAN 接口 → 扫描 → 解析 */
    public static void run(Callback cb) {
        findPath(0, path -> {
            if (path == null) {
                cb.onFailure(Failure.NOT_INSTALLED, null);
                return;
            }
            // 扫哪张网卡:优先 lan 的 L3 设备,取不到时退回常见的 br-lan
            RouterApi.getNetworkInterfaces(new ApiCallback<List<
                    com.whykangkang.wrthub.model.NetworkInterfaceInfo>>() {
                @Override
                public void onSuccess(
                        List<com.whykangkang.wrthub.model.NetworkInterfaceInfo> list) {
                    String device = "br-lan";
                    for (com.whykangkang.wrthub.model.NetworkInterfaceInfo i : list) {
                        if ("lan".equals(i.name) && i.device != null && !i.device.isEmpty()) {
                            device = i.device;
                            break;
                        }
                    }
                    execute(path, device, cb);
                }

                @Override
                public void onFailure(ApiError error) {
                    execute(path, "br-lan", cb);
                }
            });
        });
    }

    private interface PathFound {
        void onPath(String path);
    }

    /** 逐个 stat 候选路径,返回第一个存在的可执行文件 */
    private static void findPath(int index, PathFound found) {
        if (index >= PATHS.size()) {
            found.onPath(null);
            return;
        }
        String path = PATHS.get(index);
        api().fileStat(path, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String type = result.has("type") ? result.get("type").getAsString() : "";
                if ("file".equals(type) || "link".equals(type) || "symlink".equals(type)) {
                    found.onPath(path);
                } else {
                    findPath(index + 1, found);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                findPath(index + 1, found);
            }
        });
    }

    private static void execute(String path, String device, Callback cb) {
        // 用绝对路径调用:rpcd 的 ACL 按完整命令行匹配,绝对路径命中率更高
        // -l 扫本网段 / -g 忽略重复应答
        api().fileExec(path, new String[]{"-I", device, "-l", "-g"},
                OpenWrtApi.TIMEOUT_DIAGNOSTIC_SEC, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        StringBuilder sb = new StringBuilder();
                        JsonElement stdout = result.get("stdout");
                        JsonElement stderr = result.get("stderr");
                        if (stdout != null && stdout.isJsonPrimitive()) {
                            sb.append(stdout.getAsString());
                        }
                        if (stderr != null && stderr.isJsonPrimitive()) {
                            sb.append(stderr.getAsString());
                        }
                        String output = sb.toString();
                        List<Entry> entries = parseOutput(output);
                        if (entries.isEmpty() && looksDenied(output)) {
                            cb.onFailure(Failure.PERMISSION_DENIED, null);
                        } else {
                            cb.onSuccess(entries);
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        // ubus 码 6 = 权限被拒绝(ACL 没放行)
                        if (error.getType() == ApiError.Type.PERMISSION_DENIED
                                || error.getType() == ApiError.Type.ACCESS_DENIED) {
                            cb.onFailure(Failure.PERMISSION_DENIED, null);
                        } else {
                            cb.onFailure(Failure.OTHER, error.getMessage());
                        }
                    }
                });
    }

    /** 无结果时区分「网段真的没别的设备」和「命令被挡下了」 */
    static boolean looksDenied(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("permission denied")
                || lower.contains("access denied")
                || lower.contains("not permitted");
    }

    private static final Pattern LINE = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d{1,3}){3})\\s+([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\\s*(.*)$",
            Pattern.MULTILINE);

    /**
     * arp-scan 输出形如:192.168.1.23\t3c:22:fb:aa:bb:cc\tApple, Inc.
     * 头尾还有 Interface/Starting/Ending 等说明行,只取「IP + MAC」开头的数据行。
     */
    public static List<Entry> parseOutput(String text) {
        List<Entry> entries = new ArrayList<>();
        if (text == null) return entries;
        Set<String> seen = new HashSet<>();
        Matcher m = LINE.matcher(text);
        while (m.find()) {
            String mac = m.group(2).toUpperCase(Locale.ROOT);
            if (!seen.add(mac)) continue;    // -g 之外再去一次重
            String vendor = m.group(3) != null ? m.group(3).trim() : "";
            // 没有 OUI 库时 arp-scan 打印 "(Unknown)",当作没有厂商名
            if (vendor.startsWith("(")) vendor = "";
            entries.add(new Entry(m.group(1), mac, vendor));
        }
        return entries;
    }
}
