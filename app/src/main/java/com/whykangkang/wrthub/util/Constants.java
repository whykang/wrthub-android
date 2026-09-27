package com.whykangkang.wrthub.util;

/** 全局常量与 SharedPreferences 键 */
public final class Constants {

    private Constants() {
    }

    public static final String PREFS_SETTINGS = "wrthub_settings";

    public static final String KEY_AUTO_REFRESH = "auto_refresh";
    public static final String KEY_REFRESH_INTERVAL_MS = "refresh_interval_ms";
    public static final String KEY_LANGUAGE = "language";        // system / zh / en
    public static final String KEY_APPEARANCE = "appearance";    // system / light / dark
    public static final String KEY_LATENCY_TARGET = "latency_target";
    public static final String KEY_LOGIN_TIMESTAMP = "login_timestamp";
    public static final String KEY_LOGGED_IN = "is_logged_in";   // 冷启动是否直接进首页

    /** 默认刷新间隔:1 秒(对应 iOS 默认) */
    public static final long DEFAULT_REFRESH_INTERVAL_MS = 1000L;

    public static final String DEFAULT_LATENCY_TARGET = "www.baidu.com";

    /** fake-ip 场景下 Clash API 也不可用时改测这个字面 IP(纯数字不经 DNS) */
    public static final String FALLBACK_LATENCY_IP = "223.5.5.5";
}