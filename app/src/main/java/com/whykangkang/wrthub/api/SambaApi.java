package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.model.SambaShare;

import java.util.ArrayList;
import java.util.List;

/**
 * NAS(Samba4)服务 API,对应 iOS OpenWrtAPI 的 Samba Service 分区。
 *
 * 全部走 uci samba4 读写;写入后一律 uci.apply(rpcd 的方法白名单通常只有 apply)
 * 再 best-effort 重启 /etc/init.d/samba4。
 */
public final class SambaApi {

    public static final String CONFIG = "samba4";
    private static final String INIT = "samba4";

    /** getSambaShares 的返回值,对应 iOS SambaConfig */
    public static final class SambaConfig {
        public final List<SambaShare> shares = new ArrayList<>();
        public boolean enabled = true;
        public String workgroup = "WORKGROUP";
        public String description = "Samba on OpenWRT";
        /** 全局 samba 段的 section 名,写 interface 开关时用 */
        public String globalSection;
    }

    private SambaApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    /** 读取全局设置 + 所有共享 */
    public static void getShares(ApiCallback<SambaConfig> cb) {
        api().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parse(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 解析 uci get samba4 的响应(可单测) */
    static SambaConfig parse(JsonObject result) {
        SambaConfig config = new SambaConfig();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String key : values.keySet()) {
            JsonElement el = values.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject sec = el.getAsJsonObject();
            String type = str(sec, ".type", "");
            if ("samba".equals(type)) {
                config.globalSection = key;
                config.workgroup = str(sec, "workgroup", config.workgroup);
                config.description = str(sec, "description", config.description);
                // iOS: interface 非空即视为已启用
                config.enabled = !str(sec, "interface", "").isEmpty();
            } else if ("sambashare".equals(type)) {
                SambaShare share = new SambaShare();
                share.section = key;
                share.displayName = str(sec, "name", key);
                share.path = str(sec, "path", "");
                share.readOnly = "yes".equals(str(sec, "read_only", "no"));
                share.guestOk = "yes".equals(str(sec, "guest_ok", "no"));
                share.createMask = str(sec, "create_mask", "0666");
                share.dirMask = str(sec, "dir_mask", "0777");
                share.browseable = !"no".equals(str(sec, "browseable", "yes"));
                config.shares.add(share);
            }
        }
        return config;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    /**
     * 启停 Samba:写全局段的 interface(lan / 空)再 apply;
     * 找不到全局段或写失败时直接 start/stop 服务(iOS 的兼容模式)。
     */
    public static void setEnabled(boolean enabled, ApiCallback<Boolean> cb) {
        getShares(new ApiCallback<SambaConfig>() {
            @Override
            public void onSuccess(SambaConfig config) {
                if (config.globalSection == null) {
                    toggleService(enabled, cb);
                    return;
                }
                JsonObject values = new JsonObject();
                values.addProperty("interface", enabled ? "lan" : "");
                api().uciSet(CONFIG, config.globalSection, values, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        applyAndRestart(cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        toggleService(enabled, cb);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                toggleService(enabled, cb);
            }
        });
    }

    private static void toggleService(boolean enabled, ApiCallback<Boolean> cb) {
        api().initdControl(INIT, enabled ? "start" : "stop", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(true);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void addShare(String name, String path, boolean readOnly, boolean guestOk,
                                ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("name", name);
        values.addProperty("path", path);
        values.addProperty("read_only", readOnly ? "yes" : "no");
        values.addProperty("guest_ok", guestOk ? "yes" : "no");
        values.addProperty("create_mask", "0666");
        values.addProperty("dir_mask", "0777");
        values.addProperty("browseable", "yes");
        api().uciAdd(CONFIG, "sambashare", name, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                applyAndRestart(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void updateShare(String section, String name, String path,
                                   boolean readOnly, boolean guestOk, ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("name", name);
        values.addProperty("path", path);
        values.addProperty("read_only", readOnly ? "yes" : "no");
        values.addProperty("guest_ok", guestOk ? "yes" : "no");
        api().uciSet(CONFIG, section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                applyAndRestart(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void deleteShare(String section, ApiCallback<Boolean> cb) {
        api().uciDelete(CONFIG, section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                applyAndRestart(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** apply(提交并重载)后再尽力重启一次服务;重启失败不算失败,apply 已经生效 */
    private static void applyAndRestart(ApiCallback<Boolean> cb) {
        api().uciApplyOrCommit(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api().bestEffortInitAction(INIT, "restart", () -> cb.onSuccess(true));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }
}
