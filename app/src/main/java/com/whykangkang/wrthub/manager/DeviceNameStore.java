package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 设备自定义名称(按 MAC 记忆,仅存本机),
 * 对应 iOS UserDefaults 的 customName(forMAC:) / setCustomName。
 */
public final class DeviceNameStore {

    private static final String PREFS = "wrthub_device_names";

    private DeviceNameStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String mac) {
        return mac == null ? "" : mac.toUpperCase(Locale.ROOT);
    }

    /** 自定义名;未设置返回 null */
    public static String get(Context context, String mac) {
        String name = prefs(context).getString(key(mac), null);
        return name != null && !name.trim().isEmpty() ? name : null;
    }

    /** 传 null 或空串即恢复默认名称 */
    public static void set(Context context, String mac, String name) {
        SharedPreferences.Editor e = prefs(context).edit();
        if (name == null || name.trim().isEmpty()) {
            e.remove(key(mac));
        } else {
            e.putString(key(mac), name.trim());
        }
        e.apply();
    }
}
