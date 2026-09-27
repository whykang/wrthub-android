package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.whykangkang.wrthub.model.RouterDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * 多设备管理,对应 iOS 的 RouterDeviceManager。
 * 设备(含密码)存 EncryptedSharedPreferences(对应 Keychain)。
 */
public class RouterDeviceManager {

    private static final String PREFS_NAME = "wrthub_devices";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_LAST_DEVICE_ID = "last_device_id";

    private static RouterDeviceManager instance;

    public static synchronized RouterDeviceManager getInstance(Context context) {
        if (instance == null) {
            instance = new RouterDeviceManager(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    private RouterDeviceManager(Context context) {
        prefs = createPrefs(context);
    }

    private static SharedPreferences createPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Keystore 异常(极少数机型)时退回明文存储,保证功能可用
            return context.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE);
        }
    }

    public List<RouterDevice> getDevices() {
        String json = prefs.getString(KEY_DEVICES, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<RouterDevice> list = gson.fromJson(json,
                    new TypeToken<List<RouterDevice>>() {
                    }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public boolean hasDevices() {
        return !getDevices().isEmpty();
    }

    public RouterDevice getDevice(String id) {
        for (RouterDevice d : getDevices()) {
            if (d.getId().equals(id)) {
                return d;
            }
        }
        return null;
    }

    public void addDevice(RouterDevice device) {
        List<RouterDevice> devices = getDevices();
        devices.add(device);
        save(devices);
    }

    public void updateDevice(RouterDevice device) {
        List<RouterDevice> devices = getDevices();
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getId().equals(device.getId())) {
                devices.set(i, device);
                save(devices);
                return;
            }
        }
    }

    public void removeDevice(String id) {
        List<RouterDevice> devices = getDevices();
        devices.removeIf(d -> d.getId().equals(id));
        save(devices);
        if (id.equals(getLastDeviceId())) {
            prefs.edit().remove(KEY_LAST_DEVICE_ID).apply();
        }
    }

    /** 当前连接的设备(最后一次登录的那台);没有则返回 null */
    public RouterDevice getCurrentDevice() {
        String id = getLastDeviceId();
        return id == null ? null : getDevice(id);
    }

    public String getLastDeviceId() {
        return prefs.getString(KEY_LAST_DEVICE_ID, null);
    }

    public void setLastDeviceId(String id) {
        prefs.edit().putString(KEY_LAST_DEVICE_ID, id).apply();
    }

    private void save(List<RouterDevice> devices) {
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply();
    }
}