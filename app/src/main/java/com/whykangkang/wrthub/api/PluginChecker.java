package com.whykangkang.wrthub.api;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件可用性检测,对应 iOS ServicesViewController 的 check*Availability 系列。
 *
 * 检测方式与 iOS 一致:
 *  - openclash / eqos / samba4 —— file.stat 看 /etc/init.d/&lt;name&gt; 在不在;
 *  - vsftpd —— uci get vsftpd 能读到配置即视为已装。
 *
 * 结果带 30 秒节流缓存(iOS 服务页 viewWillAppear 的 checkInterval),
 * 避免来回切页每次都打一轮请求。
 */
public final class PluginChecker {

    /** 与 iOS 同名的插件标识 */
    public enum Plugin {
        OPENCLASH("openclash"),
        EQOS("eqos"),
        SAMBA("samba4"),
        FTP("vsftpd"),
        FILEBROWSER("filebrowser"),
        VNSTAT("vnstat"),
        TTYD("ttyd"),
        /** 网络唤醒:etherwake 或 wol 有一个就行,不看 init 脚本 */
        WOL("wol");

        public final String initName;

        Plugin(String initName) {
            this.initName = initName;
        }
    }

    private static final long CACHE_TTL_MS = 30 * 1000;

    private static final Map<String, Boolean> cache = new HashMap<>();
    private static final Map<String, Long> cacheTime = new HashMap<>();

    private PluginChecker() {
    }

    /** 切换设备时清掉上一台的检测结果 */
    public static synchronized void clearCache() {
        cache.clear();
        cacheTime.clear();
    }

    /** 缓存命中(30s 内)时直接返回结果,否则返回 null 表示需要发起检测 */
    public static synchronized Boolean cached(Plugin plugin) {
        Long t = cacheTime.get(key(plugin));
        if (t == null || System.currentTimeMillis() - t > CACHE_TTL_MS) {
            return null;
        }
        return cache.get(key(plugin));
    }

    private static synchronized void put(Plugin plugin, boolean installed) {
        cache.put(key(plugin), installed);
        cacheTime.put(key(plugin), System.currentTimeMillis());
    }

    private static String key(Plugin plugin) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        return api.getHost() + ":" + api.getPort() + "/" + plugin.name();
    }

    /**
     * 检测插件是否安装。命中 30s 缓存时同步回调;检测失败一律按「未安装」处理,
     * 与 iOS 行为一致(不把网络错误冒泡到服务页)。
     */
    public static void check(Plugin plugin, ApiCallback<Boolean> cb) {
        Boolean hit = cached(plugin);
        if (hit != null) {
            cb.onSuccess(hit);
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        if (!api.isConfigured()) {
            cb.onSuccess(false);
            return;
        }
        if (plugin == Plugin.WOL) {
            WolApi.checkTools(new ApiCallback<WolApi.Tools>() {
                @Override
                public void onSuccess(WolApi.Tools tools) {
                    put(plugin, tools.any());
                    cb.onSuccess(tools.any());
                }

                @Override
                public void onFailure(ApiError error) {
                    put(plugin, false);
                    cb.onSuccess(false);
                }
            });
            return;
        }
        if (plugin == Plugin.FTP) {
            // vsftpd 没有独立 init 名约定,iOS 用 uci get vsftpd 判定
            api.uciGetConfig("vsftpd", new ApiCallback<JsonObject>() {
                @Override
                public void onSuccess(JsonObject result) {
                    put(plugin, true);
                    cb.onSuccess(true);
                }

                @Override
                public void onFailure(ApiError error) {
                    put(plugin, false);
                    cb.onSuccess(false);
                }
            });
            return;
        }
        api.checkInitScriptExists(plugin.initName, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean installed) {
                put(plugin, installed);
                cb.onSuccess(installed);
            }

            @Override
            public void onFailure(ApiError error) {
                put(plugin, false);
                cb.onSuccess(false);
            }
        });
    }
}
