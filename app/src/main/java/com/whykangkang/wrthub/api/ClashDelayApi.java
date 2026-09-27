package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 经 Clash API 测「当前节点 → 目标网站」的 HTTP 延迟(OpenClash 面板同款),
 * 对应 iOS OpenWrtAPI.getClashHTTPDelay。
 *
 * fake-ip 模式下路由器无法 ping 被代理域名(ICMP 不走代理),改调 Clash RESTful API。
 * 路径一:让路由器本机 curl 回环访问 —— 不依赖 9090 对手机可达,远程管理也能用;
 * 路径二:手机直连 &lt;登录host&gt;:cn_port。
 */
public final class ClashDelayApi {

    /** 面板信息缓存(按 baseUrl) */
    private static String cachedBase;
    private static String cachedPort;
    private static String cachedSecret;

    /**
     * 这台设备的 rpcd ACL 不放行 curl(首次 file.exec 返回 6 后置位)。
     * 延迟卡是每秒刷新的,记住一次就别再每轮白跑一个必失败的 ubus 调用。
     */
    private static boolean curlDenied;

    private ClashDelayApi() {
    }

    public static synchronized void clearCache() {
        cachedBase = null;
        cachedPort = null;
        cachedSecret = null;
        curlDenied = false;
    }

    /** 返回延迟毫秒 */
    public static void getHttpDelay(String host, ApiCallback<Integer> cb) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        String base = api.baseUrl();
        if (base.equals(cachedBase) && cachedPort != null) {
            request(cachedPort, cachedSecret, host, cb);
            return;
        }
        api.openclashStatus(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject status) {
                String port = str(status, "cn_port");
                String secret = str(status, "dase");
                if (port == null || port.isEmpty()) {
                    cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "无 Clash API 端口"));
                    return;
                }
                synchronized (ClashDelayApi.class) {
                    cachedBase = base;
                    cachedPort = port;
                    cachedSecret = secret;
                }
                request(port, secret, host, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** GET /proxies/GLOBAL/delay(Meta 内核对组测当前选中节点) */
    static String delayPath(String host) {
        String target = host.contains("://") ? host : "https://" + host;
        String enc;
        try {
            enc = URLEncoder.encode(target, "UTF-8");
        } catch (Exception e) {
            enc = target;
        }
        return "/proxies/GLOBAL/delay?timeout=5000&url=" + enc;
    }

    /** 输出形如 {"delay":123} 或 {"message":"Timeout"};截 JSON 部分解析 */
    static Integer parseDelay(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonObject json = JsonParser.parseString(text.substring(start, end + 1))
                    .getAsJsonObject();
            if (json.has("delay")) return json.get("delay").getAsInt();
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static void request(String port, String secret, String host,
                                ApiCallback<Integer> cb) {
        String path = delayPath(host);
        if (curlDenied) {
            directFallback(port, secret, path, cb);
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        String[] args;
        if (secret != null && !secret.isEmpty()) {
            args = new String[]{"-s", "-m", "6", "-H", "Authorization: Bearer " + secret,
                    "http://127.0.0.1:" + port + path};
        } else {
            args = new String[]{"-s", "-m", "6", "http://127.0.0.1:" + port + path};
        }
        api.fileExec("curl", args, 10, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonElement stdout = result.get("stdout");
                Integer delay = parseDelay(stdout != null ? stdout.getAsString() : null);
                if (delay != null) {
                    cb.onSuccess(delay);
                } else {
                    directFallback(port, secret, path, cb);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                if (error.getType() == ApiError.Type.PERMISSION_DENIED
                        || error.getType() == ApiError.Type.ACCESS_DENIED) {
                    curlDenied = true;
                }
                directFallback(port, secret, path, cb);
            }
        });
    }

    /** 手机直连 http://&lt;登录host&gt;:cn_port,需 cn_port 对手机可达 */
    private static void directFallback(String port, String secret, String path,
                                       ApiCallback<Integer> cb) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.withSession(cb, () -> {
            Request.Builder builder = new Request.Builder()
                    .url("http://" + api.getHost() + ":" + port + path);
            if (secret != null && !secret.isEmpty()) {
                builder.header("Authorization", "Bearer " + secret);
            }
            try (Response response = api.clientWithTimeout(6).newCall(builder.build()).execute()) {
                String text = response.body() != null ? response.body().string() : "";
                if (response.code() != 200) {
                    api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                            "HTTP " + response.code()));
                    return;
                }
                Integer delay = parseDelay(text);
                if (delay == null) {
                    api.postFailure(cb, new ApiError(ApiError.Type.INVALID_RESPONSE,
                            "Clash 延迟响应无法解析"));
                } else {
                    api.postSuccess(cb, delay);
                }
            } catch (IOException e) {
                api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                        "网络错误: " + e.getMessage()));
            }
        });
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
