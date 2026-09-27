package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.whykangkang.wrthub.manager.ServiceCatalog.Descriptor;
import com.whykangkang.wrthub.manager.ServiceCatalog.ServiceId;
import com.whykangkang.wrthub.util.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 首页「常用功能」收藏管理(SharedPreferences 持久化,保留用户排序),
 * 对应 iOS Services/FavoriteServicesManager.swift。
 */
public class FavoriteServicesManager {

    private static final String KEY = "favoriteServiceIDs";

    private static FavoriteServicesManager instance;

    public static synchronized FavoriteServicesManager getInstance(Context context) {
        if (instance == null) {
            instance = new FavoriteServicesManager(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;

    private FavoriteServicesManager(Context context) {
        prefs = context.getSharedPreferences(Constants.PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    /** 当前收藏的功能 ID(按用户添加顺序) */
    public List<ServiceId> getFavoriteIds() {
        String raw = prefs.getString(KEY, "");
        List<ServiceId> ids = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return ids;
        for (String key : raw.split(",")) {
            ServiceId id = ServiceId.fromKey(key);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** 当前收藏的功能描述(按用户添加顺序,自动过滤已失效的 ID) */
    public List<Descriptor> getFavorites() {
        List<Descriptor> out = new ArrayList<>();
        for (ServiceId id : getFavoriteIds()) {
            Descriptor d = ServiceCatalog.descriptor(id);
            if (d != null) out.add(d);
        }
        return out;
    }

    public void setFavorites(List<ServiceId> ids) {
        StringBuilder sb = new StringBuilder();
        for (ServiceId id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id.key);
        }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }

    public boolean contains(ServiceId id) {
        return getFavoriteIds().contains(id);
    }

    /** 切换收藏状态:已收藏则移除,未收藏则追加到末尾 */
    public void toggle(ServiceId id) {
        List<ServiceId> ids = getFavoriteIds();
        if (!ids.remove(id)) {
            ids.add(id);
        }
        setFavorites(ids);
    }

    /** 首次使用时的默认收藏(与 iOS 一致:为空,由用户自行添加) */
    public boolean isEmpty() {
        return getFavoriteIds().isEmpty();
    }

    static List<ServiceId> defaultsForTest() {
        return new ArrayList<>(Arrays.asList(ServiceId.WEB_ACCESS));
    }
}
