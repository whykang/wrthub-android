package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 记录每个 MAC 被加入黑名单的时间,对应 iOS 存在 UserDefaults 里的 addedDate。
 *
 * 黑名单本身以路由器上的防火墙规则为准(比 iOS 的纯本地列表可靠),
 * 这里只额外存一个时间戳用于展示;查不到就不显示「加入时间」那一行。
 */
public final class BlacklistDateStore {

    private static final String PREFS = "wrthub_blacklist_dates";

    private BlacklistDateStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String mac) {
        return mac == null ? "" : mac.toUpperCase(Locale.ROOT);
    }

    public static void markAdded(Context context, String mac) {
        if (mac == null || mac.isEmpty()) return;
        prefs(context).edit().putLong(key(mac), System.currentTimeMillis()).apply();
    }

    public static void clear(Context context, String mac) {
        if (mac == null || mac.isEmpty()) return;
        prefs(context).edit().remove(key(mac)).apply();
    }

    /** 0 表示没有记录(例如在路由器上直接加的规则) */
    public static long getAdded(Context context, String mac) {
        if (mac == null || mac.isEmpty()) return 0;
        return prefs(context).getLong(key(mac), 0);
    }
}
