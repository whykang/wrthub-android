package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本更新检测:拉取 {@link #UPDATE_URL} 上的 JSON,与本地 versionCode 比对。
 *
 * <pre>
 * {
 *   "versionCode": 2,            // 整数,大于本地版本才提示更新
 *   "versionName": "1.1.0",      // 展示用版本名
 *   "forceUpdate": false,        // true = 必须更新
 *   "downloadUrl": "https://…",  // 安装包地址,点「立即更新」用外部浏览器打开
 *   "updateMessage": "…"         // 更新说明
 * }
 * </pre>
 *
 * 这里不复用 OpenWrtApi 的 OkHttpClient —— 那个是按路由器调的
 * (禁跟随重定向、10 秒超时、带 sysauth cookie),不适合访问公网站点。
 */
public final class UpdateChecker {

    public static final String UPDATE_URL = "https://whykangkang.com/wrthub_update.json";

    /** 服务端返回的版本信息 */
    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final boolean forceUpdate;
        public final String downloadUrl;
        public final String updateMessage;

        UpdateInfo(int versionCode, String versionName, boolean forceUpdate,
                   String downloadUrl, String updateMessage) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.forceUpdate = forceUpdate;
            this.downloadUrl = downloadUrl;
            this.updateMessage = updateMessage;
        }
    }

    public interface Callback {
        /** info 为 null 表示已是最新版本 */
        void onResult(UpdateInfo info);

        void onFailure(String message);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** 懒加载:静态初始化 Handler 会让纯 JVM 单测(没有 Looper)连类都加载不了 */
    private static Handler main;

    private UpdateChecker() {
    }

    /** 本地 versionCode;取不到时按 1 处理 */
    public static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return (int) info.getLongVersionCode();
        } catch (Exception e) {
            return 1;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 检查更新,回调在主线程 */
    public static void check(Context context, Callback callback) {
        int localCode = currentVersionCode(context);
        EXECUTOR.execute(() -> {
            String body;
            try (Response response = CLIENT.newCall(
                    new Request.Builder().url(UPDATE_URL).build()).execute()) {
                if (!response.isSuccessful()) {
                    post(() -> callback.onFailure("HTTP " + response.code()));
                    return;
                }
                body = response.body() != null ? response.body().string() : "";
            } catch (IOException e) {
                post(() -> callback.onFailure(e.getMessage() == null
                        ? "network error" : e.getMessage()));
                return;
            }

            UpdateInfo info = parse(body);
            if (info == null) {
                post(() -> callback.onFailure("invalid response"));
                return;
            }
            // 只有服务端版本号更大才算有更新
            post(() -> callback.onResult(info.versionCode > localCode ? info : null));
        });
    }

    /** 可单测:JSON → UpdateInfo;缺字段或格式不对返回 null */
    public static UpdateInfo parse(String body) {
        if (body == null) return null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("versionCode")) return null;
            return new UpdateInfo(
                    json.get("versionCode").getAsInt(),
                    str(json, "versionName", ""),
                    json.has("forceUpdate") && json.get("forceUpdate").getAsBoolean(),
                    str(json, "downloadUrl", ""),
                    str(json, "updateMessage", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject json, String key, String def) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString() : def;
    }

    private static synchronized void post(Runnable action) {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        main.post(action);
    }
}
