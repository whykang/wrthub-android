package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 局域网测速的历史记录,存在本机。
 *
 * **按路由器分开存**:测的是"手机 ↔ 这台路由器"的链路,
 * 换一台设备结果就没有可比性,混在一起看会误导。
 */
public final class SpeedTestHistory {

    /** 只留最近这么多条,再多列表也没人往下翻 */
    public static final int MAX_RECORDS = 20;

    private static final String PREFS = "wrthub_speedtest";

    public static final class Record {
        public final long timestamp;
        public final double downBps;
        public final double upBps;
        public final double latencyMs;
        public final double jitterMs;

        public Record(long timestamp, double downBps, double upBps,
                      double latencyMs, double jitterMs) {
            this.timestamp = timestamp;
            this.downBps = downBps;
            this.upBps = upBps;
            this.latencyMs = latencyMs;
            this.jitterMs = jitterMs;
        }
    }

    private SpeedTestHistory() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String host) {
        return "history." + (host == null || host.isEmpty() ? "unknown" : host);
    }

    public static List<Record> load(Context context, String host) {
        return parse(prefs(context).getString(key(host), null));
    }

    /** 新记录插到最前面,超出上限的丢掉 */
    public static void add(Context context, String host, Record record) {
        List<Record> list = load(context, host);
        list.add(0, record);
        while (list.size() > MAX_RECORDS) {
            list.remove(list.size() - 1);
        }
        prefs(context).edit().putString(key(host), serialize(list)).apply();
    }

    public static void clear(Context context, String host) {
        prefs(context).edit().remove(key(host)).apply();
    }

    // =====================================================================
    // 序列化(可单测)
    // =====================================================================

    public static String serialize(List<Record> records) {
        JsonArray arr = new JsonArray();
        for (Record r : records) {
            JsonObject o = new JsonObject();
            o.addProperty("t", r.timestamp);
            o.addProperty("d", r.downBps);
            o.addProperty("u", r.upBps);
            o.addProperty("l", r.latencyMs);
            o.addProperty("j", r.jitterMs);
            arr.add(o);
        }
        return arr.toString();
    }

    public static List<Record> parse(String text) {
        List<Record> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(text).getAsJsonArray();
        } catch (Exception e) {
            // 存坏了就当没有,不要让一条坏数据把整页搞崩
            return out;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            try {
                out.add(new Record(num(o, "t").longValue(), num(o, "d"), num(o, "u"),
                        num(o, "l"), num(o, "j")));
            } catch (Exception ignored) {
                // 跳过这一条
            }
        }
        return out;
    }

    private static Double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0d;
    }
}
