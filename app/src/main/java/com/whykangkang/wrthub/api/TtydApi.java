package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 终端(luci-app-ttyd)相关的读取,对应 iOS OpenWrtAPI 的 ttyd 扩展。
 *
 * 与网页端 term.js 的做法一致:读 uci `ttyd` 第一个实例拿端口 / SSL / url_override,
 * 再用 service.list 看服务在不在跑。
 */
public final class TtydApi {

    /** uci `ttyd` 第一个实例里与连接有关的字段(与网页端 term.js 读的一致) */
    public static final class Config {
        public boolean enabled = true;
        public String port = "7681";
        public boolean ssl;
        /** 配了就直接用这个地址 */
        public String urlOverride;
        /** Basic 认证,形如 "user:password";没配为 null */
        public String credential;
        /** 绑定到 UNIX socket 时根本没有 TCP 端口,App 连不上 */
        public boolean unixSocket;
    }

    private TtydApi() {
    }

    public static void loadConfig(ApiCallback<Config> cb) {
        OpenWrtApi.getInstance().uciGetConfig("ttyd", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseConfig(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /**
     * 可单测。取**第一个** ttyd 段 —— 网页端 uci.get_first 同样只看第一个实例。
     * uci 返回的是对象,顺序靠 .index 排,不能直接拿第一项。
     */
    public static Config parseConfig(JsonObject data) {
        Config config = new Config();
        if (data == null || !data.has("values") || !data.get("values").isJsonObject()) {
            return config;
        }
        JsonObject first = null;
        int firstIndex = Integer.MAX_VALUE;
        for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("values").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject s = e.getValue().getAsJsonObject();
            if (!"ttyd".equals(str(s, ".type"))) continue;
            int index = s.has(".index") ? s.get(".index").getAsInt() : 0;
            if (index < firstIndex) {
                firstIndex = index;
                first = s;
            }
        }
        if (first == null) return config;

        // enable 默认开(网页端 o.default = true)
        String enable = str(first, "enable");
        if (enable != null) config.enabled = !"0".equals(enable);
        String port = str(first, "port");
        if (port != null && !port.isEmpty()) config.port = port;
        config.ssl = "1".equals(str(first, "ssl"));
        config.unixSocket = "1".equals(str(first, "unix_sock"));
        String override = str(first, "url_override");
        if (override != null && !override.isEmpty()) config.urlOverride = override;
        String credential = str(first, "credential");
        if (credential != null && credential.contains(":")) config.credential = credential;
        return config;
    }

    /** ttyd 服务是否在跑;查不到按没在跑处理 */
    public static void isRunning(ApiCallback<Boolean> cb) {
        OpenWrtApi.getInstance().serviceList("ttyd", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(OpenWrtApi.serviceIsRunning(result, "ttyd"));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onSuccess(false);
            }
        });
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }
}
