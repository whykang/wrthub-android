package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * FileBrowser 的账号密码。
 *
 * 它和路由器后台是**两套独立账号** —— FileBrowser 自己有用户表,
 * 不能拿 LuCI 的凭据顶上。按路由器地址分别保存,和设备列表一样走
 * EncryptedSharedPreferences。
 */
public final class FileBrowserAccount {

    public static final class Credentials {
        public final String username;
        public final String password;

        public Credentials(String username, String password) {
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        public boolean isEmpty() {
            return username.isEmpty();
        }
    }

    private static final String PREFS = "wrthub_filebrowser";

    private FileBrowserAccount() {
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app)
                    .setKeyGenParameterSpec(new KeyGenParameterSpec.Builder(
                            MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build())
                    .build();
            return EncryptedSharedPreferences.create(app, PREFS, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // 加密库不可用时退回普通存储,和 RouterDeviceManager 一样的兜底
            return app.getSharedPreferences(PREFS + "_plain", Context.MODE_PRIVATE);
        }
    }

    public static Credentials load(Context context, String host) {
        SharedPreferences p = prefs(context);
        return new Credentials(p.getString(host + ".user", ""), p.getString(host + ".pass", ""));
    }

    public static void save(Context context, String host, String username, String password) {
        prefs(context).edit()
                .putString(host + ".user", username)
                .putString(host + ".pass", password)
                .apply();
    }

    public static void clear(Context context, String host) {
        prefs(context).edit().remove(host + ".user").remove(host + ".pass").apply();
    }
}
