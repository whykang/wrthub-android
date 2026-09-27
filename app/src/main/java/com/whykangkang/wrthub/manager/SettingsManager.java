package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.whykangkang.wrthub.util.Constants;

/** 应用设置,对应 iOS 的 UserDefaults 封装 */
public class SettingsManager {

    private static SettingsManager instance;

    public static synchronized SettingsManager getInstance(Context context) {
        if (instance == null) {
            instance = new SettingsManager(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;

    private SettingsManager(Context context) {
        prefs = context.getSharedPreferences(Constants.PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    public boolean isAutoRefresh() {
        return prefs.getBoolean(Constants.KEY_AUTO_REFRESH, true);
    }

    public void setAutoRefresh(boolean enabled) {
        prefs.edit().putBoolean(Constants.KEY_AUTO_REFRESH, enabled).apply();
    }

    public long getRefreshIntervalMs() {
        return prefs.getLong(Constants.KEY_REFRESH_INTERVAL_MS, Constants.DEFAULT_REFRESH_INTERVAL_MS);
    }

    public void setRefreshIntervalMs(long ms) {
        prefs.edit().putLong(Constants.KEY_REFRESH_INTERVAL_MS, ms).apply();
    }

    public String getLanguage() {
        return prefs.getString(Constants.KEY_LANGUAGE, "system");
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(Constants.KEY_LANGUAGE, lang).apply();
    }

    public String getAppearance() {
        return prefs.getString(Constants.KEY_APPEARANCE, "system");
    }

    public void setAppearance(String appearance) {
        prefs.edit().putString(Constants.KEY_APPEARANCE, appearance).apply();
    }

    /** 最近一次成功登录的时刻(设置页「连接信息」显示) */
    /**
     * 是否处于已登录状态。对应 iOS 的 UserDefaults "isLoggedIn":
     * 为 true 时冷启动跳过设备列表直接进首页,退出登录时清掉。
     */
    public boolean isLoggedIn() {
        return prefs.getBoolean(Constants.KEY_LOGGED_IN, false);
    }

    public void setLoggedIn(boolean loggedIn) {
        prefs.edit().putBoolean(Constants.KEY_LOGGED_IN, loggedIn).apply();
    }

    public long getLoginTimestamp() {
        return prefs.getLong(Constants.KEY_LOGIN_TIMESTAMP, 0L);
    }

    public void setLoginTimestamp(long millis) {
        prefs.edit().putLong(Constants.KEY_LOGIN_TIMESTAMP, millis).apply();
    }

    public String getLatencyTarget() {
        return prefs.getString(Constants.KEY_LATENCY_TARGET, Constants.DEFAULT_LATENCY_TARGET);
    }

    public void setLatencyTarget(String target) {
        prefs.edit().putString(Constants.KEY_LATENCY_TARGET, target).apply();
    }
}