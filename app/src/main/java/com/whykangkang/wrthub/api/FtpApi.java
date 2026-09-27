package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * FTP(vsftpd)服务 API,对应 iOS OpenWrtAPI 的 FTP Service 分区。
 * 配置读写走 uci vsftpd;写后 uci.apply 再 best-effort 重启 /etc/init.d/vsftpd。
 */
public final class FtpApi {

    public static final String CONFIG = "vsftpd";
    private static final String INIT = "vsftpd";

    /** vsftpd listen 段 */
    public static final class FtpConfig {
        public String port = "21";
        public boolean enable4 = true;
    }

    /** 一个 FTP 用户(uci user 段) */
    public static final class FtpUser {
        public String section;
        public String username = "";
        public String home = "";
    }

    private FtpApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    public static void getConfig(ApiCallback<FtpConfig> cb) {
        api().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
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

    static FtpConfig parseConfig(JsonObject result) {
        FtpConfig config = new FtpConfig();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        JsonElement listenEl = values.get("listen");
        if (listenEl != null && listenEl.isJsonObject()) {
            JsonObject listen = listenEl.getAsJsonObject();
            config.port = str(listen, "port", "21");
            config.enable4 = "1".equals(str(listen, "enable4", "1"));
        }
        return config;
    }

    public static void getUsers(ApiCallback<List<FtpUser>> cb) {
        api().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parseUsers(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    static List<FtpUser> parseUsers(JsonObject result) {
        List<FtpUser> users = new ArrayList<>();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String key : values.keySet()) {
            JsonElement el = values.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject sec = el.getAsJsonObject();
            if (!"user".equals(str(sec, ".type", ""))) continue;
            FtpUser u = new FtpUser();
            u.section = str(sec, ".name", key);
            u.username = str(sec, "username", "");
            u.home = str(sec, "home", "");
            users.add(u);
        }
        return users;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    public static void updateConfig(String port, boolean enable4, ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("port", port);
        values.addProperty("enable4", enable4 ? "1" : "0");
        api().uciSet(CONFIG, "listen", values, new ApiCallback<JsonObject>() {
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

    public static void addUser(String username, String password, String home,
                               ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("enabled", "1");
        values.addProperty("username", username);
        values.addProperty("password", password);
        values.addProperty("home", home);
        values.addProperty("umask", "022");
        values.addProperty("write_enable", "1");
        values.addProperty("upload_enable", "1");
        api().uciAdd(CONFIG, "user", null, values, new ApiCallback<JsonObject>() {
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

    public static void deleteUser(String section, ApiCallback<Boolean> cb) {
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
