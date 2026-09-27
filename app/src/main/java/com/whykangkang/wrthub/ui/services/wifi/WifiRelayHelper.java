package com.whykangkang.wrthub.ui.services.wifi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * 添加 WiFi 为中继(STA)并接入 wwan,对应 iOS 的中继 wwan 逻辑(§2.4)。
 * 步骤:
 *  1. 新增 wireless wifi-iface(mode=sta,network=wwan);
 *  2. 读整份 network 配置判断 wwan 接口是否存在(iOS 修过:uci get 对不存在 section 返回
 *     [0] 的坑,必须读整配置判断),不存在则创建 proto=dhcp 的 wwan;
 *  3. 把 wwan 加入 wan 防火墙 zone 的 network 列表;
 *  4. commit wireless/network/firewall 并 reload。
 */
public class WifiRelayHelper {

    public interface Callback {
        /** section 为新建 wifi-iface 的段名(部分固件不回传时为 null) */
        void onDone(String section);

        void onError(ApiError error);
    }

    private static final String WWAN = "wwan";

    private final OpenWrtApi api;

    public WifiRelayHelper(OpenWrtApi api) {
        this.api = api;
    }

    /**
     * disabled=true 时先以停用状态加入 —— 密码填错的话启用会把无线服务搞挂,
     * 所以与 iOS 一致:先加进去,再由界面询问是否立即启用。
     */
    public void addStaRelay(String radioDevice, String ssid, String key,
                            String encryption, boolean disabled, Callback cb) {
        JsonObject values = new JsonObject();
        values.addProperty("device", radioDevice);
        values.addProperty("mode", "sta");
        values.addProperty("ssid", ssid);
        values.addProperty("network", WWAN);
        values.addProperty("disabled", disabled ? "1" : "0");
        if (encryption != null && !"none".equals(encryption)) {
            values.addProperty("encryption", encryption);
            if (key != null) values.addProperty("key", key);
        } else {
            values.addProperty("encryption", "none");
        }

        api.uciAdd("wireless", "wifi-iface", null, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String section = result.has("section") && !result.get("section").isJsonNull()
                        ? result.get("section").getAsString() : null;
                ensureWwanInterface(section, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onError(error);
            }
        });
    }

    /** 读整份 network 配置判断 wwan 是否存在 */
    private void ensureWwanInterface(String section, Callback cb) {
        api.uciGetConfig("network", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject config) {
                JsonObject values = extractValues(config);
                boolean exists = false;
                for (String sec : values.keySet()) {
                    if (WWAN.equals(sec) && values.get(sec).isJsonObject()) {
                        JsonObject o = values.getAsJsonObject(sec);
                        if (o.has(".type") && "interface".equals(o.get(".type").getAsString())) {
                            exists = true;
                            break;
                        }
                    }
                }
                if (exists) {
                    addWwanToWanZone(section, cb);
                } else {
                    JsonObject wwanVals = new JsonObject();
                    wwanVals.addProperty("proto", "dhcp");
                    api.uciSetTyped("network", WWAN, "interface", wwanVals,
                            new ApiCallback<JsonObject>() {
                                @Override
                                public void onSuccess(JsonObject r) {
                                    addWwanToWanZone(section, cb);
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    cb.onError(error);
                                }
                            });
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onError(error);
            }
        });
    }

    private void addWwanToWanZone(String section, Callback cb) {
        api.uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject config) {
                JsonObject values = extractValues(config);
                String wanZoneSection = null;
                JsonArray networks = new JsonArray();
                for (String sec : values.keySet()) {
                    if (!values.get(sec).isJsonObject()) continue;
                    JsonObject o = values.getAsJsonObject(sec);
                    boolean isZone = o.has(".type") && "zone".equals(o.get(".type").getAsString());
                    if (isZone && o.has("name") && "wan".equals(o.get("name").getAsString())) {
                        wanZoneSection = sec;
                        networks = readList(o, "network");
                        break;
                    }
                }
                if (wanZoneSection == null) {
                    // 没有 wan zone(少见),跳过防火墙步骤直接提交
                    commitAll(section, cb);
                    return;
                }
                if (!listContains(networks, WWAN)) {
                    networks.add(WWAN);
                }
                JsonObject vals = new JsonObject();
                vals.add("network", networks);
                api.uciSet("firewall", wanZoneSection, vals, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        commitAll(section, cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onError(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onError(error);
            }
        });
    }

    private void commitAll(String section, Callback cb) {
        api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r1) {
                api.uciApplyOrCommit("network", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r2) {
                        api.uciApplyOrCommit("firewall", new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject r3) {
                                reloadAll(section, cb);
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                cb.onError(error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onError(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onError(error);
            }
        });
    }

    private void reloadAll(String section, Callback cb) {
        api.fileExec("/sbin/wifi", new String[]{"reload"}, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                finishReload(section, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                finishReload(section, cb);   // wifi reload 失败不致命,配置已提交
            }
        });
    }

    private void finishReload(String section, Callback cb) {
        api.initdControl("network", "reload", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                cb.onDone(section);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onDone(section);
            }
        });
    }

    // ---- 工具 ----

    static JsonObject extractValues(JsonObject config) {
        return config.has("values") && config.get("values").isJsonObject()
                ? config.getAsJsonObject("values") : config;
    }

    /** uci list 字段可能是数组或单值,统一读成数组 */
    static JsonArray readList(JsonObject o, String key) {
        JsonArray arr = new JsonArray();
        if (!o.has(key) || o.get(key).isJsonNull()) return arr;
        JsonElement el = o.get(key);
        if (el.isJsonArray()) {
            return el.getAsJsonArray();
        }
        // 单值(空格分隔)
        for (String part : el.getAsString().trim().split("\\s+")) {
            if (!part.isEmpty()) arr.add(part);
        }
        return arr;
    }

    static boolean listContains(JsonArray arr, String value) {
        for (JsonElement e : arr) {
            if (value.equals(e.getAsString())) return true;
        }
        return false;
    }
}