package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.util.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 首页顶部指标方块的显示配置:6 个指标可自定义显隐(最少 2 个,默认全显示)。
 * 设置入口见 设置 → 首页编辑。对应 iOS Utils/HomeMetricsConfig.swift。
 */
public final class HomeMetricsConfig {

    /** 首页指标方块(顺序即首页展示顺序) */
    public enum HomeMetric {
        CPU("cpu", R.string.metric_cpu, R.drawable.metric_cpu),
        MEMORY("memory", R.string.metric_memory, R.drawable.metric_memory),
        CONNECTIONS("connections", R.string.metric_connections, R.drawable.metric_connections),
        DEVICES("devices", R.string.metric_devices, R.drawable.metric_devices),
        SPEED("speed", R.string.metric_speed, R.drawable.metric_speed),
        LATENCY("latency", R.string.metric_latency, R.drawable.metric_latency);

        public final String key;
        @StringRes
        public final int titleRes;
        @DrawableRes
        public final int iconRes;

        HomeMetric(String key, int titleRes, int iconRes) {
            this.key = key;
            this.titleRes = titleRes;
            this.iconRes = iconRes;
        }

        static HomeMetric fromKey(String key) {
            for (HomeMetric m : values()) {
                if (m.key.equals(key)) return m;
            }
            return null;
        }
    }

    private static final String KEY = "dashboard.visibleMetrics";
    public static final int MIN_VISIBLE = 2;

    private HomeMetricsConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(Constants.PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    /** 当前可见指标(按固定顺序)。未配置或少于下限时回退为全部显示。 */
    public static List<HomeMetric> visible(Context ctx) {
        String raw = prefs(ctx).getString(KEY, null);
        if (raw == null) {
            return new ArrayList<>(Arrays.asList(HomeMetric.values()));
        }
        Set<HomeMetric> set = new LinkedHashSet<>();
        for (String key : raw.split(",")) {
            HomeMetric m = HomeMetric.fromKey(key);
            if (m != null) set.add(m);
        }
        List<HomeMetric> result = new ArrayList<>();
        for (HomeMetric m : HomeMetric.values()) {
            if (set.contains(m)) result.add(m);
        }
        return result.size() >= MIN_VISIBLE
                ? result : new ArrayList<>(Arrays.asList(HomeMetric.values()));
    }

    public static boolean isVisible(Context ctx, HomeMetric metric) {
        return visible(ctx).contains(metric);
    }

    public static void setVisible(Context ctx, List<HomeMetric> metrics) {
        StringBuilder sb = new StringBuilder();
        for (HomeMetric m : metrics) {
            if (sb.length() > 0) sb.append(',');
            sb.append(m.key);
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    /** 切换某项显隐。关闭会导致低于下限时返回 false(调用方提示用户)。 */
    public static boolean toggle(Context ctx, HomeMetric metric) {
        List<HomeMetric> current = visible(ctx);
        if (current.contains(metric)) {
            if (current.size() <= MIN_VISIBLE) return false;
            current.remove(metric);
        } else {
            current.add(metric);
        }
        // 归一为固定顺序
        List<HomeMetric> ordered = new ArrayList<>();
        for (HomeMetric m : HomeMetric.values()) {
            if (current.contains(m)) ordered.add(m);
        }
        setVisible(ctx, ordered);
        return true;
    }
}
