package com.whykangkang.wrthub.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * vnStat 流量监控(luci-app-vnstat2)。
 *
 * 接口按真机实测确定(vnStat 2.11,jsonversion 2):
 * <pre>
 *  数据    file.exec /usr/bin/vnstat --json &lt;模式&gt; → 按周期分开取,见 loadTraffic
 *  库内接口 file.exec /usr/bin/vnstat --dbiflist 1 → 每行一个接口名
 *  运行状态 ubus service.list {"name":"vnstat"}
 *  监控接口 uci vnstat.@vnstat[0].interface        → **列表**,如 ["br-lan","eth1"]
 * </pre>
 *
 * 网页端画的是 vnstati 生成的 PNG,这里不走那条路 —— 直接吃 --json 的结构化数据
 * 自己渲染,手机上缩放和深色模式都比一张固定尺寸的图好办。
 */
public final class VnstatApi {

    /** uci 里那个匿名 section 的类型名;整份配置只有一段 */
    public static final String CONFIG = "vnstat";

    /** 一个时间段的流量 */
    public static final class Entry {
        public final String label;
        public final long rx;
        public final long tx;
        /** 这个时间段有多长(秒),用来算平均速率;0 表示未知 */
        public final long periodSeconds;

        public Entry(String label, long rx, long tx) {
            this(label, rx, tx, 0);
        }

        public Entry(String label, long rx, long tx, long periodSeconds) {
            this.label = label;
            this.rx = rx;
            this.tx = tx;
            this.periodSeconds = periodSeconds;
        }

        public long total() {
            return rx + tx;
        }

        /**
         * 平均速率(bit/s),算法与 vnStat 的 "avg. rate" 一致:
         * 总字节 × 8 ÷ 该时段的**标称**长度。
         *
         * 注意最新那一条时段还没走完,分母仍按整段算,所以读数会偏低 ——
         * vnStat 自己也是这么显示的,跟网页端对得上。
         */
        public double avgBitsPerSecond() {
            if (periodSeconds <= 0) return 0;
            return total() * 8.0 / periodSeconds;
        }
    }

    /** 一个被监控接口的全部数据 */
    public static final class Interface {
        public final String name;
        public long totalRx;
        public long totalTx;
        public final List<Entry> fiveMinutes = new ArrayList<>();
        public final List<Entry> hours = new ArrayList<>();
        public final List<Entry> days = new ArrayList<>();
        public final List<Entry> months = new ArrayList<>();
        /** 流量最大的那些天,对应 vnstati 的 Top 视图 */
        public final List<Entry> tops = new ArrayList<>();
        /** 建库时间 / 最后更新时间,摘要里显示 */
        public String created = "";
        public String updated = "";
        /** 同上,但是 epoch 秒 —— 算累计平均速率要用真实跨度,不能用格式化后的字符串 */
        public long createdTs;
        public long updatedTs;

        /** 累计平均速率(bit/s):总流量 ÷ 实际记录时长。时长未知时返回 0。 */
        public double avgBitsPerSecond() {
            long span = updatedTs - createdTs;
            if (span <= 0) return 0;
            return (totalRx + totalTx) * 8.0 / span;
        }

        Interface(String name) {
            this.name = name;
        }
    }

    private VnstatApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    /**
     * 全部接口的流量数据(与 iOS loadVnstatTraffic 相同)。
     *
     * **不能一次 vnstat --json 全取**:rpcd 的 file.exec 输出上限约 256KB,超了整条调用
     * 直接返回 ubus 状态 8,一个字节都拿不到。真机实测 4 个接口时完整 JSON 约 295KB,
     * 其中 5 分钟数据就占 245KB(每个接口 48 小时 ≈ 586 条)—— 多监控一个接口就会突然全挂。
     *
     * 所以分开取,每次都远低于上限:
     *  1. --json m:接口列表 + 累计 + 建库/更新时间 + 月(几百字节/接口)
     *  2. --json d、--json t:天、排行,所有接口一起取也只有几 KB
     *  3. 每个接口单独 -i &lt;名字&gt; --json h 和 --json f:单接口 13KB / 80KB 左右
     * 再按接口名合并。某一项失败只让那一栏空着,不拖累其它数据。
     */
    public static void loadTraffic(ApiCallback<List<Interface>> cb) {
        runJson(new String[]{"--json", "m"}, new ApiCallback<List<Interface>>() {
            @Override
            public void onSuccess(List<Interface> base) {
                if (base.isEmpty()) {
                    cb.onSuccess(base);
                    return;
                }
                List<String[]> requests = new ArrayList<>();
                requests.add(new String[]{"--json", "d"});
                requests.add(new String[]{"--json", "t"});
                for (Interface iface : base) {
                    requests.add(new String[]{"-i", iface.name, "--json", "h"});
                    requests.add(new String[]{"-i", iface.name, "--json", "f"});
                }
                // 回调都在主线程,计数和合并不用加锁
                int[] pending = {requests.size()};
                for (String[] args : requests) {
                    runJson(args, new ApiCallback<List<Interface>>() {
                        @Override
                        public void onSuccess(List<Interface> part) {
                            merge(base, part);
                            if (--pending[0] == 0) cb.onSuccess(base);
                        }

                        @Override
                        public void onFailure(ApiError error) {
                            if (--pending[0] == 0) cb.onSuccess(base);
                        }
                    });
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    private static void runJson(String[] args, ApiCallback<List<Interface>> cb) {
        api().fileExec("/usr/bin/vnstat", args, 30, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String stdout = result.has("stdout") && !result.get("stdout").isJsonNull()
                        ? result.get("stdout").getAsString() : "";
                cb.onSuccess(parseJson(stdout));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:把只含部分周期的结果按接口名并进来(每个周期只填还空着的) */
    static void merge(List<Interface> base, List<Interface> part) {
        for (Interface p : part) {
            for (Interface b : base) {
                if (!b.name.equals(p.name)) continue;
                if (b.fiveMinutes.isEmpty()) b.fiveMinutes.addAll(p.fiveMinutes);
                if (b.hours.isEmpty()) b.hours.addAll(p.hours);
                if (b.days.isEmpty()) b.days.addAll(p.days);
                if (b.months.isEmpty()) b.months.addAll(p.months);
                if (b.tops.isEmpty()) b.tops.addAll(p.tops);
                break;
            }
        }
    }

    /** 可单测:vnstat --json 的输出 → 接口列表 */
    public static List<Interface> parseJson(String text) {
        List<Interface> result = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return result;
        }
        if (!root.has("interfaces") || !root.get("interfaces").isJsonArray()) return result;
        for (JsonElement el : root.getAsJsonArray("interfaces")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Interface iface = new Interface(str(o, "name"));
            if (iface.name.isEmpty()) continue;
            iface.created = stamp(o, "created");
            iface.updated = stamp(o, "updated");
            iface.createdTs = timestamp(o, "created");
            iface.updatedTs = timestamp(o, "updated");
            if (o.has("traffic") && o.get("traffic").isJsonObject()) {
                JsonObject traffic = o.getAsJsonObject("traffic");
                if (traffic.has("total") && traffic.get("total").isJsonObject()) {
                    JsonObject total = traffic.getAsJsonObject("total");
                    iface.totalRx = num(total, "rx");
                    iface.totalTx = num(total, "tx");
                }
                collect(traffic, "fiveminute", iface.fiveMinutes, Period.MINUTE);
                collect(traffic, "hour", iface.hours, Period.HOUR);
                collect(traffic, "day", iface.days, Period.DAY);
                collect(traffic, "month", iface.months, Period.MONTH);
                collect(traffic, "top", iface.tops, Period.DAY);
            }
            result.add(iface);
        }
        return result;
    }

    private enum Period { MINUTE, HOUR, DAY, MONTH }

    /** 该时段的标称长度(秒)。月份要按当月实际天数算,不能一律 30 天。 */
    static long periodSeconds(JsonObject entry, Period period) {
        switch (period) {
            case MINUTE:
                return 300;
            case HOUR:
                return 3600;
            case MONTH: {
                JsonObject date = entry.has("date") && entry.get("date").isJsonObject()
                        ? entry.getAsJsonObject("date") : null;
                int year = date != null ? (int) num(date, "year") : 0;
                int month = date != null ? (int) num(date, "month") : 0;
                return daysInMonth(year, month) * 86400L;
            }
            default:
                return 86400;
        }
    }

    static int daysInMonth(int year, int month) {
        if (month < 1 || month > 12) return 30;
        switch (month) {
            case 2:
                boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
                return leap ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** created / updated 节点里的 epoch 秒 */
    static long timestamp(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) return 0;
        return num(o.getAsJsonObject(key), "timestamp");
    }

    /** created / updated 这类带 date(+可选 time)的节点 → 可读字符串 */
    static String stamp(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) return "";
        JsonObject node = o.getAsJsonObject(key);
        if (!node.has("date") || !node.get("date").isJsonObject()) return "";
        JsonObject date = node.getAsJsonObject("date");
        String text = String.format(Locale.US, "%04d-%02d-%02d",
                (int) num(date, "year"), (int) num(date, "month"), (int) num(date, "day"));
        if (node.has("time") && node.get("time").isJsonObject()) {
            JsonObject time = node.getAsJsonObject("time");
            text += String.format(Locale.US, " %02d:%02d",
                    (int) num(time, "hour"), (int) num(time, "minute"));
        }
        return text;
    }

    private static void collect(JsonObject traffic, String key, List<Entry> out, Period period) {
        if (!traffic.has(key) || !traffic.get(key).isJsonArray()) return;
        JsonArray arr = traffic.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new Entry(label(o, period), num(o, "rx"), num(o, "tx"),
                    periodSeconds(o, period)));
        }
        // vnstat 给的是旧 → 新,展示时最近的排前面
        Collections.reverse(out);
    }

    /** 可单测:按周期拼出人看的标签 */
    static String label(JsonObject entry, Period period) {
        JsonObject date = entry.has("date") && entry.get("date").isJsonObject()
                ? entry.getAsJsonObject("date") : null;
        if (date == null) return "";
        int year = (int) num(date, "year");
        int month = (int) num(date, "month");
        int day = (int) num(date, "day");
        switch (period) {
            case MONTH:
                return String.format(Locale.US, "%04d-%02d", year, month);
            case HOUR: {
                JsonObject time = entry.has("time") && entry.get("time").isJsonObject()
                        ? entry.getAsJsonObject("time") : null;
                int hour = time != null ? (int) num(time, "hour") : 0;
                return String.format(Locale.US, "%02d-%02d %02d:00", month, day, hour);
            }
            case MINUTE: {
                JsonObject time = entry.has("time") && entry.get("time").isJsonObject()
                        ? entry.getAsJsonObject("time") : null;
                int hour = time != null ? (int) num(time, "hour") : 0;
                int minute = time != null ? (int) num(time, "minute") : 0;
                return String.format(Locale.US, "%02d:%02d", hour, minute);
            }
            default:
                return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
        }
    }

    // =====================================================================
    // 配置
    // =====================================================================

    /** 当前被监控的接口(uci vnstat.@vnstat[0].interface) */
    public static void loadMonitored(ApiCallback<List<String>> cb) {
        api().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseMonitored(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:uci 响应 → 监控接口列表。section 是匿名的,按 .type 找。 */
    public static List<String> parseMonitored(JsonObject uci) {
        List<String> names = new ArrayList<>();
        JsonObject values = uci != null && uci.has("values") && uci.get("values").isJsonObject()
                ? uci.getAsJsonObject("values") : uci;
        if (values == null) return names;
        for (String key : values.keySet()) {
            if (!values.get(key).isJsonObject()) continue;
            JsonObject section = values.getAsJsonObject(key);
            if (!CONFIG.equals(str(section, ".type"))) continue;
            JsonElement iface = section.get("interface");
            if (iface == null || iface.isJsonNull()) continue;
            if (iface.isJsonArray()) {
                for (JsonElement e : iface.getAsJsonArray()) names.add(e.getAsString());
            } else {
                // 只配了一个接口时 uci 可能回字符串而不是数组
                for (String part : iface.getAsString().split("\\s+")) {
                    if (!part.isEmpty()) names.add(part);
                }
            }
            break;
        }
        return names;
    }

    /**
     * 确保 uci 里有那个 vnstat 段。
     * 有就回它的名字;整份配置还不存在(全新安装)时新建一个匿名段 ——
     * 不建的话「保存」无处可写,界面只能一直显示「没有 vnStat」。
     */
    public static void ensureSection(ApiCallback<String> cb) {
        OpenWrtApi api = api();
        api.uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject uci) {
                String section = findSection(uci);
                if (section != null) {
                    cb.onSuccess(section);
                } else {
                    addSection(cb);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
                    addSection(cb);
                } else {
                    cb.onFailure(error);
                }
            }
        });
    }

    private static void addSection(ApiCallback<String> cb) {
        api().uciAdd(CONFIG, CONFIG, null, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String name = result != null && result.has("section")
                        ? result.get("section").getAsString() : null;
                if (name == null) {
                    cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, "无法创建 vnstat 配置段"));
                } else {
                    cb.onSuccess(name);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** uci 里那个匿名 section 的名字(如 cfg015065),写回时要用 */
    public static String findSection(JsonObject uci) {
        JsonObject values = uci != null && uci.has("values") && uci.get("values").isJsonObject()
                ? uci.getAsJsonObject("values") : uci;
        if (values == null) return null;
        for (String key : values.keySet()) {
            if (!values.get(key).isJsonObject()) continue;
            if (CONFIG.equals(str(values.getAsJsonObject(key), ".type"))) return key;
        }
        return null;
    }

    /** 数据库里已有记录的接口(可能包含已从配置里移除的) */
    public static void loadDatabaseInterfaces(ApiCallback<List<String>> cb) {
        api().fileExec("/usr/bin/vnstat", new String[]{"--dbiflist", "1"}, 20,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        List<String> names = new ArrayList<>();
                        String stdout = result.has("stdout") && !result.get("stdout").isJsonNull()
                                ? result.get("stdout").getAsString() : "";
                        for (String line : stdout.split("\n")) {
                            String name = line.trim();
                            if (!name.isEmpty()) names.add(name);
                        }
                        cb.onSuccess(names);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /**
     * 保存监控接口。interface 是 **list** 选项,必须写 JSON 数组 ——
     * 写成空格分隔的字符串 uci 会当成单个值,vnstatd 起不来。
     */
    public static void saveMonitored(String section, List<String> interfaces,
                                     ApiCallback<JsonObject> cb) {
        JsonArray arr = new JsonArray();
        for (String name : interfaces) arr.add(name);
        JsonObject values = new JsonObject();
        values.add("interface", arr);
        OpenWrtApi api = api();
        api.uciSetTyped(CONFIG, section, CONFIG, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit(CONFIG, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        // 改了监控接口要重启 vnstatd 才会去建库
                        api.bestEffortInitAction(CONFIG, "restart", () -> cb.onSuccess(r));
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

    /** 清空某个接口的历史数据(先移除再重新加入,与网页端一致) */
    public static void resetInterface(String iface, ApiCallback<JsonObject> cb) {
        OpenWrtApi api = api();
        api.fileExec("/usr/bin/vnstat", new String[]{"--remove", "-i", iface, "--force"}, 30,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        api.fileExec("/usr/bin/vnstat", new String[]{"--add", "-i", iface}, 30, cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    public static void serviceRunning(ApiCallback<Boolean> cb) {
        api().serviceList(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(OpenWrtApi.serviceIsRunning(result, CONFIG));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    // =====================================================================

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
