package com.whykangkang.wrthub.api;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.manager.DeviceNameStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WOL 网络唤醒,对应 iOS OpenWrtAPI 的 WOL 扩展。
 *
 * 参数与网页端 luci-app-wol 完全一致(见 /luci-static/resources/view/wol.js):
 *  - etherwake: {@code -D -i <iface> [-b] <mac>}
 *  - wol:       {@code -v <mac>}
 *
 * rpcd 的 ACL 只放行这两个固定路径(luci-app-wol 的 acl.d:
 * /usr/bin/etherwake 与 /usr/bin/wol 的 exec 权限),所以不能换别的命令。
 */
public final class WolApi {

    public static final String ETHERWAKE = "/usr/bin/etherwake";
    public static final String WOL = "/usr/bin/wol";

    private WolApi() {
    }

    /** 两个唤醒工具,固件上不一定都装 */
    public static final class Tools {
        public boolean etherwake;
        public boolean wol;

        public boolean any() {
            return etherwake || wol;
        }

        /** 默认用哪个。etherwake 能指定网卡和广播,优先它。 */
        public String preferred() {
            return etherwake ? ETHERWAKE : WOL;
        }
    }

    /** 可唤醒的主机(来自 luci-rpc getHostHints) */
    public static final class Host {
        public final String mac;
        /** 路由器报上来的主机名。真机实测大半设备**没有**这个字段 */
        public final String name;
        public final String ip;

        public Host(String mac, String name, String ip) {
            this.mac = mac;
            this.name = name == null ? "" : name;
            this.ip = ip == null ? "" : ip;
        }

        /**
         * 括号里显示的名字,优先级:「设备」页里起的名字 &gt; 主机名 &gt; IPv4/IPv6。
         * 与网页端 {@code mac (name || ipv4 || ipv6 || '?')} 同一套退化顺序。
         */
        public String label(Context c) {
            String nick = DeviceNameStore.get(c, mac);
            if (nick != null) return nick;
            if (!name.isEmpty()) return name;
            if (!ip.isEmpty()) return ip;
            return "?";
        }

        /** 与网页端一致:"AA:BB:CC:DD:EE:FF (书房电脑)" */
        public String display(Context c) {
            return mac + " (" + label(c) + ")";
        }

        boolean hasName(Context c) {
            return DeviceNameStore.get(c, mac) != null || !name.isEmpty();
        }
    }

    /** 发包网卡候选 */
    public static final class Iface {
        public final String name;
        public final String type;

        Iface(String name, String type) {
            this.name = name;
            this.type = type == null ? "" : type;
        }
    }

    /** 探测装了哪个唤醒工具。与网页端一致:file.stat 两个路径。回调在主线程。 */
    public static void checkTools(ApiCallback<Tools> cb) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        Tools tools = new Tools();
        int[] pending = {2};
        Runnable done = () -> {
            if (--pending[0] == 0) cb.onSuccess(tools);
        };
        api.fileStat(ETHERWAKE, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                tools.etherwake = r != null && r.has("type");
                done.run();
            }

            @Override
            public void onFailure(ApiError e) {
                done.run();
            }
        });
        api.fileStat(WOL, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                tools.wol = r != null && r.has("type");
                done.run();
            }

            @Override
            public void onFailure(ApiError e) {
                done.run();
            }
        });
    }

    /** 局域网里见过的主机,供选择唤醒目标;失败返回空列表 */
    public static void loadHosts(Context context, ApiCallback<List<Host>> cb) {
        Context app = context.getApplicationContext();
        OpenWrtApi.getInstance().getHostHints(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseHosts(app, result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onSuccess(new ArrayList<>());
            }
        });
    }

    /** 可单测:getHostHints 响应 → 主机列表。有名字的排前面,其余按显示名排。 */
    public static List<Host> parseHosts(Context c, JsonObject data) {
        List<Host> hosts = new ArrayList<>();
        if (data == null) return hosts;
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            // 字段名各版本不一样:ipaddrs / ipv4 都见过
            String v4 = first(o, "ipaddrs", "ipv4");
            String v6 = first(o, "ip6addrs", "ipv6");
            String name = o.has("name") && o.get("name").isJsonPrimitive()
                    ? o.get("name").getAsString() : "";
            hosts.add(new Host(e.getKey().toUpperCase(Locale.ROOT), name,
                    v4 != null ? v4 : (v6 != null ? v6 : "")));
        }
        // 有名字的排前面 —— 几十台里只有几台有名字,一律按 MAC 排的话要找的那台会埋在中间
        hosts.sort((a, b) -> {
            boolean an = a.hasName(c), bn = b.hasName(c);
            if (an != bn) return an ? -1 : 1;
            return a.label(c).compareToIgnoreCase(b.label(c));
        });
        return hosts;
    }

    private static String first(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonArray()) {
                JsonArray arr = o.getAsJsonArray(k);
                if (arr.size() > 0 && arr.get(0).isJsonPrimitive()) return arr.get(0).getAsString();
            }
        }
        return null;
    }

    /**
     * 发包网卡候选(网页端的 widgets.DeviceSelect)。
     *
     * 数据源是 luci-rpc getNetworkDevices,过滤规则照搬网页端的
     * noaliases / noinactive:跳过没 up 的(如 sit0)和回环 lo。
     *
     * 注意 uci etherwake.setup 里**没有** interface 这个选项(真机实测),
     * 网页端那句 uci.get('etherwake','setup','interface') 永远是空,所以默认值得自己挑。
     */
    public static void loadInterfaces(ApiCallback<List<Iface>> cb) {
        OpenWrtApi.getInstance().getNetworkDevices(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseInterfaces(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onSuccess(new ArrayList<>());
            }
        });
    }

    /** 可单测:网桥排最前(唤醒包通常要从 br-lan 发),其余按名字 */
    public static List<Iface> parseInterfaces(JsonObject data) {
        List<Iface> list = new ArrayList<>();
        if (data == null) return list;
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if ("lo".equals(e.getKey()) || !e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("up") && o.get("up").isJsonPrimitive() && !o.get("up").getAsBoolean()) continue;
            String type = o.has("devtype") && o.get("devtype").isJsonPrimitive()
                    ? o.get("devtype").getAsString() : "";
            list.add(new Iface(e.getKey(), type));
        }
        list.sort((a, b) -> {
            boolean ab = "bridge".equals(a.type), bb = "bridge".equals(b.type);
            if (ab != bb) return ab ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    /**
     * 发唤醒包。参数与网页端 handleWakeup 完全一致。成功回调 stdout。
     *
     * 必须看退出码:真机实测 etherwake 出错时是 code=3 + stderr 一句话,
     * 不看退出码界面就会报「已发送」,实际上一个包都没发出去。
     */
    public static void wake(String mac, String executable, String iface, boolean broadcast,
                            ApiCallback<String> cb) {
        List<String> args = new ArrayList<>();
        if (ETHERWAKE.equals(executable)) {
            args.add("-D");
            if (iface != null && !iface.isEmpty()) {
                args.add("-i");
                args.add(iface);
            }
            if (broadcast) args.add("-b");
            args.add(mac);
        } else {
            // wol 自己找网卡,没有 iface / broadcast 选项
            args.add("-v");
            args.add(mac);
        }
        OpenWrtApi.getInstance().fileExec(executable, args.toArray(new String[0]), 20,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        int code = r.has("code") ? r.get("code").getAsInt() : 0;
                        String stdout = str(r, "stdout");
                        String stderr = str(r, "stderr");
                        if (code == 0) {
                            cb.onSuccess(stdout);
                        } else {
                            String detail = (stderr.isEmpty() ? stdout : stderr).trim();
                            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR,
                                    detail + " (exit " + code + ")", code));
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /** 可单测:MAC 规范化成 AA:BB:CC:DD:EE:FF;不合法返回 null */
    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        StringBuilder hex = new StringBuilder();
        for (char ch : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) hex.append(ch);
        }
        if (hex.length() != 12) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (out.length() > 0) out.append(':');
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
