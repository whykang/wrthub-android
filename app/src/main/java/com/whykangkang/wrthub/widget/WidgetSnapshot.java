package com.whykangkang.wrthub.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 桌面小组件的数据快照,对应 iOS Utils/WidgetSnapshotWriter.swift。
 * 主 App 刷新首页时写入,小组件读取渲染;键名两侧必须保持一致。
 */
public final class WidgetSnapshot {

    public static final String PREFS = "wrthub_widget";

    private static final String KEY_NAME = "widget.routerName";
    private static final String KEY_DOWN = "widget.downBps";
    private static final String KEY_UP = "widget.upBps";
    private static final String KEY_LATENCY = "widget.latencyMs";
    private static final String KEY_LATENCY_PROXY = "widget.latencyProxy";
    private static final String KEY_DEVICES = "widget.devices";
    private static final String KEY_CPU = "widget.cpuPercent";
    private static final String KEY_MEM = "widget.memPercent";
    private static final String KEY_WAN_IP = "widget.wanIP";
    private static final String KEY_UPDATED = "widget.updatedAt";
    private static final String KEY_PRIMARY = "widget.cfg.primary";
    private static final String KEY_ITEMS = "widget.cfg.items";

    /** 距上次时间线刷新至少间隔:首页 1 秒刷新,别把小组件更新烧成电量问题 */
    private static final long RELOAD_INTERVAL_MS = 60_000;
    private static long lastReload;

    private WidgetSnapshot() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 写入快照。数值为 null 表示该项无数据(组件端显示 --)。 */
    public static void write(Context context, String routerName,
                             Double downBps, Double upBps,
                             Double latencyMs, boolean latencyViaProxy,
                             Integer devices, Double cpuPercent, Double memPercent,
                             String wanIp) {
        SharedPreferences.Editor e = prefs(context).edit();
        e.putString(KEY_NAME, routerName);
        e.putFloat(KEY_DOWN, downBps != null ? downBps.floatValue() : 0f);
        e.putFloat(KEY_UP, upBps != null ? upBps.floatValue() : 0f);
        putOrRemove(e, KEY_LATENCY, latencyMs);
        e.putBoolean(KEY_LATENCY_PROXY, latencyViaProxy);
        putOrRemove(e, KEY_DEVICES, devices != null ? (double) devices : null);
        putOrRemove(e, KEY_CPU, cpuPercent);
        putOrRemove(e, KEY_MEM, memPercent);
        e.putString(KEY_WAN_IP, wanIp != null ? wanIp : "");
        e.putLong(KEY_UPDATED, System.currentTimeMillis());
        e.apply();

        // 节流刷新组件
        long now = System.currentTimeMillis();
        if (now - lastReload >= RELOAD_INTERVAL_MS) {
            lastReload = now;
            notifyWidgets(context);
        }
    }

    /** 强制刷新(退后台等关键时机,不受节流限制) */
    public static void forceReload(Context context) {
        lastReload = System.currentTimeMillis();
        notifyWidgets(context);
    }

    private static void notifyWidgets(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        ComponentName component = new ComponentName(app, WrtHubWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length > 0) {
            WrtHubWidgetProvider.renderAll(app, manager, ids);
        }
    }

    private static void putOrRemove(SharedPreferences.Editor e, String key, Double value) {
        if (value != null) {
            e.putFloat(key, value.floatValue());
        } else {
            e.remove(key);
        }
    }

    // =====================================================================
    // 读取
    // =====================================================================

    /** 小组件可展示的指标(与 iOS WidgetMetric 一致) */
    public enum Metric {
        SPEED("speed"),
        LATENCY("latency"),
        DEVICES("devices"),
        CPU("cpu"),
        MEMORY("memory");

        public final String key;

        Metric(String key) {
            this.key = key;
        }

        public static Metric fromKey(String key) {
            for (Metric m : values()) {
                if (m.key.equals(key)) return m;
            }
            return null;
        }
    }

    /** 小组件最多显示 4 个次要指标(与 iOS maxItems 一致) */
    public static final int MAX_ITEMS = 4;

    /** 主参数(大字显示位),默认网速 */
    public static Metric primary(Context context) {
        Metric m = Metric.fromKey(prefs(context).getString(KEY_PRIMARY, null));
        return m != null ? m : Metric.SPEED;
    }

    public static void setPrimary(Context context, Metric metric) {
        prefs(context).edit().putString(KEY_PRIMARY, metric.key).apply();
        forceReload(context);
    }

    /** 次要指标列表,默认 在线设备/延迟/CPU/内存(与 iOS 默认一致) */
    public static List<Metric> items(Context context) {
        String raw = prefs(context).getString(KEY_ITEMS, null);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>(Arrays.asList(
                    Metric.DEVICES, Metric.LATENCY, Metric.CPU, Metric.MEMORY));
        }
        List<Metric> out = new ArrayList<>();
        for (String key : raw.split(",")) {
            Metric m = Metric.fromKey(key);
            if (m != null && !out.contains(m)) out.add(m);
        }
        return out.isEmpty()
                ? new ArrayList<>(Arrays.asList(
                Metric.DEVICES, Metric.LATENCY, Metric.CPU, Metric.MEMORY))
                : out;
    }

    public static void setItems(Context context, List<Metric> metrics) {
        StringBuilder sb = new StringBuilder();
        for (Metric m : metrics) {
            if (sb.length() > 0) sb.append(',');
            sb.append(m.key);
        }
        prefs(context).edit().putString(KEY_ITEMS, sb.toString()).apply();
        forceReload(context);
    }

    public static String routerName(Context c) {
        return prefs(c).getString(KEY_NAME, "OpenWrt");
    }

    public static float downBps(Context c) {
        return prefs(c).getFloat(KEY_DOWN, 0f);
    }

    public static float upBps(Context c) {
        return prefs(c).getFloat(KEY_UP, 0f);
    }

    public static Float latencyMs(Context c) {
        return prefs(c).contains(KEY_LATENCY) ? prefs(c).getFloat(KEY_LATENCY, 0f) : null;
    }

    public static boolean latencyViaProxy(Context c) {
        return prefs(c).getBoolean(KEY_LATENCY_PROXY, false);
    }

    public static Integer devices(Context c) {
        return prefs(c).contains(KEY_DEVICES)
                ? Math.round(prefs(c).getFloat(KEY_DEVICES, 0f)) : null;
    }

    public static Float cpuPercent(Context c) {
        return prefs(c).contains(KEY_CPU) ? prefs(c).getFloat(KEY_CPU, 0f) : null;
    }

    public static Float memPercent(Context c) {
        return prefs(c).contains(KEY_MEM) ? prefs(c).getFloat(KEY_MEM, 0f) : null;
    }

    public static String wanIp(Context c) {
        return prefs(c).getString(KEY_WAN_IP, "");
    }

    public static long updatedAt(Context c) {
        return prefs(c).getLong(KEY_UPDATED, 0L);
    }

    /** 距上次更新超过 15 分钟视为过期(与 iOS isStale 一致) */
    public static boolean isStale(Context c) {
        long at = updatedAt(c);
        return at == 0 || System.currentTimeMillis() - at > 15 * 60 * 1000L;
    }
}
