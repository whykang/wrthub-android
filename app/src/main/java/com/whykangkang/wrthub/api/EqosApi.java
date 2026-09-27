package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.model.EqosDevice;
import com.whykangkang.wrthub.model.EqosGlobal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备限速(luci-app-eqos)API,对应 iOS OpenWrtAPI 的「设备限速」分区。
 *
 * 读写都走 uci —— luci-app-eqos 的 ACL 只授权了 uci scope,不含执行 init 脚本。
 * 生效靠 uci.apply 触发 /sbin/reload_config,procd 的 reload trigger 重载 eqos。
 * 注意 rpcd 的 ubus 方法白名单里只有 apply 没有 commit,直接 commit 会 -32002。
 */
public final class EqosApi {

    public static final String CONFIG = "eqos";
    private static final String INIT = "eqos";

    /** 全局设置 + 设备列表 */
    public static final class EqosSettings {
        public EqosGlobal global = EqosGlobal.defaults();
        public final List<EqosDevice> devices = new ArrayList<>();
    }

    private EqosApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    public static void getSettings(ApiCallback<EqosSettings> cb) {
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

    static EqosSettings parse(JsonObject result) {
        EqosSettings out = new EqosSettings();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String key : values.keySet()) {
            JsonElement el = values.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject sec = el.getAsJsonObject();
            String type = str(sec, ".type", "");
            if ("eqos".equals(type)) {
                out.global = new EqosGlobal(
                        uciBool(sec, "enabled", false),
                        uciInt(sec, "download", EqosGlobal.DEFAULT_DOWNLOAD),
                        uciInt(sec, "upload", EqosGlobal.DEFAULT_UPLOAD));
            } else if ("device".equals(type)) {
                String ip = str(sec, "ip", "");
                // 没有 ip 的段是坏数据,跳过(限速规则离了 IP 无意义)
                if (ip.isEmpty()) continue;
                EqosDevice d = new EqosDevice();
                d.section = key;
                d.enabled = uciBool(sec, "enabled", true);
                d.ip = ip;
                d.download = uciInt(sec, "download", 0);
                d.upload = uciInt(sec, "upload", 0);
                d.comment = str(sec, "comment", "");
                out.devices.add(d);
            }
        }
        // IP 按段比较,避免 .10 排在 .9 前面
        Collections.sort(out.devices, (a, b) -> EqosDevice.compareIp(a.ip, b.ip));
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    /** uci 的值一律是字符串,"1"/"true"/"on"/"yes"/"enabled" 都算真 */
    static boolean uciBool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        String s = e.getAsString().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("on")
                || s.equals("yes") || s.equals("enabled");
    }

    static int uciInt(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return Integer.parseInt(e.getAsString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static void saveGlobal(EqosGlobal global, ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("enabled", global.enabled ? "1" : "0");
        values.addProperty("download", String.valueOf(global.download));
        values.addProperty("upload", String.valueOf(global.upload));
        api().uciSetTyped(CONFIG, "config", "eqos", values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(global.enabled, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 新增限速设备,成功后回传 uci 生成的段名 */
    public static void addDevice(EqosDevice device, ApiCallback<String> cb) {
        api().uciAdd(CONFIG, "device", null, deviceValues(device),
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        String section = result.has("section")
                                ? result.get("section").getAsString() : "";
                        apply(null, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                cb.onSuccess(section);
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                cb.onFailure(error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    public static void updateDevice(EqosDevice device, ApiCallback<Boolean> cb) {
        if (device.section == null || device.section.isEmpty()) {
            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "缺少配置段名"));
            return;
        }
        api().uciSet(CONFIG, device.section, deviceValues(device),
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        apply(null, cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    public static void deleteDevice(String section, ApiCallback<Boolean> cb) {
        api().uciDelete(CONFIG, section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(null, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    private static JsonObject deviceValues(EqosDevice d) {
        JsonObject v = new JsonObject();
        // 每台设备自己也有启用开关,必须显式写回,
        // 否则会把用户在网页端停用的条目悄悄改成启用
        v.addProperty("enabled", d.enabled ? "1" : "0");
        v.addProperty("ip", d.ip);
        v.addProperty("download", String.valueOf(d.download));
        v.addProperty("upload", String.valueOf(d.upload));
        v.addProperty("comment", d.comment == null ? "" : d.comment);
        return v;
    }

    /**
     * apply 让配置落盘并触发 reload_config。随后的 init 动作只是给没注册
     * reload trigger 的固件兜底,被 ACL 拒属正常,不算失败。
     */
    private static void apply(Boolean enable, ApiCallback<Boolean> cb) {
        api().uciApplyOrCommit(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                Runnable finish = () -> cb.onSuccess(true);
                if (Boolean.TRUE.equals(enable)) {
                    api().bestEffortInitAction(INIT, "enable",
                            () -> api().bestEffortInitAction(INIT, "restart", finish));
                } else {
                    api().bestEffortInitAction(INIT, "restart", finish);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }
}
