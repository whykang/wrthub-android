package com.whykangkang.wrthub.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenWrt API 单例,对应 iOS 的 Services/OpenWrtAPI.swift。
 *
 * 双通道认证(§2.1):
 *  1. HTTP 表单登录(LuCI CGI 用)—— 禁止自动跟随重定向,从 302 解析 sysauth_http;
 *     登录前清空该 host 的旧 cookie,失败时清掉已存 token(设备切换串号 bug 复刻修复);
 *     表单失败不阻塞,继续 ubus 登录。
 *  2. ubus JSON-RPC 登录 —— token 5 分钟有效,按 age 预判过期自动重登。
 *
 * makeUbusCall(§2.2):
 *  - code!=0 映射错误(4/6/-32002);
 *  - -32002 时白名单方法静默失败不重登(重登风暴 bug 修复),其余重登一次并重试;
 *  - 响应字节 lossy UTF-8 解码后再进 JSON 解析(非法 SSID 崩溃修复)。
 */
public class OpenWrtApi {

    private static final int TIMEOUT_DEFAULT_SEC = 10;
    private static final int TIMEOUT_LOGIN_SEC = 15;
    public static final int TIMEOUT_DIAGNOSTIC_SEC = 60;

    /** ubus token 有效期 5 分钟,提前 30 秒视为过期 */
    private static final long TOKEN_LIFETIME_MS = 5 * 60 * 1000;
    private static final long TOKEN_MARGIN_MS = 30 * 1000;

    private static final String NULL_SESSION = "00000000000000000000000000000000";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 无权限时不触发重新登录的方法白名单(§2.2) */
    private static final Set<String> OPTIONAL_ACL_METHODS = new HashSet<>(Arrays.asList(
            "luci.getCPUInfo", "luci.getCPUUsage", "luci.getTempInfo", "network.reload",
            // 日志是 file.exec 失败后的兜底通道,它再 -32002 只说明这台固件两条路都没开,
            // 不是会话过期,重登也没用,还会把人卡在重登循环里
            "log.read"));

    private static OpenWrtApi instance;

    public static synchronized OpenWrtApi getInstance() {
        if (instance == null) {
            instance = new OpenWrtApi();
        }
        return instance;
    }

    // ---- 连接配置 ----
    private String scheme = "http";
    private String host;
    private int port = 80;
    private String username;
    private String password;

    // ---- 会话状态 ----
    private volatile String ubusToken;
    private volatile long tokenTimestampMs;
    /** hostKey(host:port) -> sysauth_http token,按 host 保存 */
    private final Map<String, String> sysauthTokens = new ConcurrentHashMap<>();
    private final Object loginLock = new Object();
    /** 上次清理连接池的时刻,用于 2 秒节流 */
    private volatile long lastSessionResetMs;

    private final OkHttpClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile Executor callbackExecutor;

    OpenWrtApi() {
        client = new OkHttpClient.Builder()
                .followRedirects(false)           // 表单登录需从 302 解析 Set-Cookie
                .followSslRedirects(false)
                .connectTimeout(TIMEOUT_DEFAULT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_DEFAULT_SEC, TimeUnit.SECONDS)
                .build();
    }

    // =====================================================================
    // 配置
    // =====================================================================

    public synchronized void configure(String host, int port, boolean useHttps,
                                       String username, String password) {
        this.host = host;
        this.port = port;
        this.scheme = useHttps ? "https" : "http";
        this.username = username;
        this.password = password;
        // 切换设备必须作废旧会话,并清空该 host 的旧 sysauth cookie(iOS 串号 bug)
        this.ubusToken = null;
        this.tokenTimestampMs = 0;
        sysauthTokens.remove(hostKey());
    }

    public synchronized void logout() {
        ubusToken = null;
        tokenTimestampMs = 0;
        if (host != null) {
            sysauthTokens.remove(hostKey());
        }
    }

    /**
     * 测试连接:用独立实例登录一次,不影响当前单例的会话状态。
     * 供添加/编辑设备页的"测试连接"用。
     */
    public static void testConnection(String host, int port, boolean useHttps,
                                      String username, String password,
                                      ApiCallback<Void> cb) {
        OpenWrtApi probe = new OpenWrtApi();
        probe.configure(host, port, useHttps, username, password);
        probe.login(cb);
    }

    public boolean isConfigured() {
        return host != null && !host.isEmpty();
    }

    /**
     * 回到前台等场景下丢弃可能已失效的复用连接。
     *
     * App 长时间挂起后系统会悄悄断开复用的 TCP 连接,但连接池里看着仍「存活」,
     * 于是首个请求要一直等到超时(10s)才失败重连。提前清掉能省掉这段干等。
     * 2 秒内只清一次,避免多个界面同时回到前台重复清理(对应 iOS resetSessionIfStale)。
     */
    public void resetSessionIfStale() {
        long now = System.currentTimeMillis();
        synchronized (loginLock) {
            if (now - lastSessionResetMs < 2000) return;
            lastSessionResetMs = now;
        }
        client.connectionPool().evictAll();
    }

    /** token 是否仍在有效期内(5 分钟,提前 30s 预判) */
    public boolean isTokenFresh() {
        return ubusToken != null
                && System.currentTimeMillis() - tokenTimestampMs < TOKEN_LIFETIME_MS - TOKEN_MARGIN_MS;
    }

    /** 测试用:替换回调线程(默认主线程) */
    void setCallbackExecutor(Executor executor) {
        this.callbackExecutor = executor;
    }

    String hostKey() {
        return host + ":" + port;
    }

    String baseUrl() {
        return scheme + "://" + host + ":" + port;
    }

    /** 当前登录主机(路由器 IP / 域名),供 Clash 面板、FTP 客户端等直连场景用 */
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    /** 当前 ubus 会话 token(可能为 null),供 cgi-io 等旁路通道拼参数 */
    String sessionToken() {
        return ubusToken;
    }

    /** 后台线程池,供同类的功能模块复用 */
    ExecutorService backgroundExecutor() {
        return executor;
    }

    /**
     * 确保已登录后在后台线程执行 body。token 过期会先重登;
     * 登录失败时把错误投递给 cb 并不执行 body。
     */
    <T> void withSession(ApiCallback<T> cb, Runnable body) {
        if (!isConfigured()) {
            postFailure(cb, new ApiError(ApiError.Type.NOT_CONFIGURED, "未配置设备"));
            return;
        }
        executor.execute(() -> {
            if (!isTokenFresh()) {
                ApiError err = loginSync();
                if (err != null) {
                    postFailure(cb, err);
                    return;
                }
            }
            body.run();
        });
    }

    // =====================================================================
    // 登录(双通道)
    // =====================================================================

    public void login(ApiCallback<Void> cb) {
        if (!isConfigured()) {
            postFailure(cb, new ApiError(ApiError.Type.NOT_CONFIGURED, "未配置设备"));
            return;
        }
        executor.execute(() -> {
            ApiError err = loginSync();
            if (err == null) {
                postSuccess(cb, null);
            } else {
                postFailure(cb, err);
            }
        });
    }

    /** 同步登录:先表单(best-effort)再 ubus。成功返回 null。 */
    private ApiError loginSync() {
        return loginSync(null);
    }

    /**
     * @param invalidToken 已确认失效的 token(-32002 场景);为 null 时表示常规登录。
     *                     token 新鲜且不是这个失效值时跳过(等锁期间其他线程已完成登录)。
     */
    private ApiError loginSync(String invalidToken) {
        synchronized (loginLock) {
            if (isTokenFresh() && (invalidToken == null || !invalidToken.equals(ubusToken))) {
                return null;
            }
            performFormLoginSync();
            ApiError error = performUbusLoginSync();
            if (error == null || !loginFailureIsTransient(error)) {
                return error;
            }
            // 静默重登撞上路由器一时繁忙是常事,隔一下再试一次,
            // 别把这种抖动当成密码错误弹给用户。密码真错就不重试了。
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error;
            }
            performFormLoginSync();
            ApiError retryError = performUbusLoginSync();
            return retryError == null ? null : retryError;
        }
    }

    /**
     * HTTP 表单登录(§2.1.1)。失败不阻塞 —— 部分固件无 CGI 表单。
     */
    private void performFormLoginSync() {
        String key = hostKey();
        // 登录前清空旧 cookie
        sysauthTokens.remove(key);

        RequestBody form = new FormBody.Builder()
                .add("luci_username", username == null ? "" : username)
                .add("luci_password", password == null ? "" : password)
                .build();
        Request request = new Request.Builder()
                .url(baseUrl() + "/cgi-bin/luci/")
                .post(form)
                .build();

        try (Response response = clientWithTimeout(TIMEOUT_LOGIN_SEC).newCall(request).execute()) {
            String token = null;
            if (response.code() == 302) {
                for (String setCookie : response.headers("Set-Cookie")) {
                    String v = extractCookie(setCookie, "sysauth_http");
                    if (v == null) {
                        v = extractCookie(setCookie, "sysauth");
                    }
                    if (v != null && !v.isEmpty()) {
                        token = v;
                    }
                }
            }
            if (token != null) {
                sysauthTokens.put(key, token);
            }
            // 失败(非 302 / 无 cookie)时保持已清空状态,即"清掉已存的 sysauth_http"
        } catch (IOException ignored) {
            // 表单通道失败静默,继续 ubus 登录
        }
    }

    /** 从 Set-Cookie 头解析指定 cookie 的值(可单测) */
    static String extractCookie(String setCookieHeader, String name) {
        if (setCookieHeader == null) return null;
        for (String part : setCookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    /**
     * 登录失败的原因分类,逐码对齐 iOS。
     *
     * **只有 code 6 才是「用户名或密码错误」。** 原来把 UBUS_ERROR 也算进去了,
     * 而它是所有非 4/6 状态码和所有 JSON-RPC 层错误的兜底类型 ——
     * 路由器一时繁忙(7 超时)、rpcd 正在重启,都会被说成密码错,
     * 于是后台静默重登偶尔失败时,界面就冒出一句「登录失败:用户名或密码错误」。
     */
    static ApiError loginError(ApiError raw) {
        switch (raw.getUbusCode()) {
            case 6:
                return new ApiError(ApiError.Type.AUTH_FAILED, "用户名或密码错误", 6);
            case 4:
                return new ApiError(ApiError.Type.UBUS_ERROR, "登录接口不可用", 4);
            case 7:
                return new ApiError(ApiError.Type.NETWORK, "路由器响应超时", 7);
            case 0:
                // JSON-RPC 层错误或网络错误,原样透传
                return raw;
            default:
                return new ApiError(raw.getType(),
                        "登录被拒绝(错误码 " + raw.getUbusCode() + ")", raw.getUbusCode());
        }
    }

    /** 这次登录失败是不是「换个时机重试就可能成功」 */
    static boolean loginFailureIsTransient(ApiError error) {
        return error != null && error.getType() != ApiError.Type.AUTH_FAILED;
    }

    /** ubus JSON-RPC 登录(§2.1.2)。成功返回 null。 */
    private ApiError performUbusLoginSync() {
        JsonObject loginParams = new JsonObject();
        loginParams.addProperty("username", username == null ? "" : username);
        loginParams.addProperty("password", password == null ? "" : password);

        UbusResult res = callUbusRawSync(NULL_SESSION, "session", "login",
                loginParams, TIMEOUT_LOGIN_SEC);
        if (res.error != null) {
            return loginError(res.error);
        }
        JsonElement tokenEl = res.data.get("ubus_rpc_session");
        if (tokenEl == null) {
            return new ApiError(ApiError.Type.INVALID_RESPONSE, "登录失败:未知响应格式");
        }
        ubusToken = tokenEl.getAsString();
        tokenTimestampMs = System.currentTimeMillis();  // 记录登录时刻,供 isTokenFresh()
        return null;
    }

    // =====================================================================
    // makeUbusCall —— 所有 ubus 调用的唯一入口(§2.2)
    // =====================================================================

    public void makeUbusCall(String object, String method, JsonObject params,
                             Integer timeoutSec, ApiCallback<JsonObject> cb) {
        if (!isConfigured()) {
            postFailure(cb, new ApiError(ApiError.Type.NOT_CONFIGURED, "未配置设备"));
            return;
        }
        int timeout = timeoutSec != null ? timeoutSec : TIMEOUT_DEFAULT_SEC;
        executor.execute(() -> {
            JsonObject p = params != null ? params : new JsonObject();

            // 1. 确保已登录(token 过期预判自动重登)
            if (!isTokenFresh()) {
                ApiError err = loginSync();
                if (err != null) {
                    postFailure(cb, err);
                    return;
                }
            }

            // 2. 调用
            String session = ubusToken;
            UbusResult res = callUbusRawSync(session, object, method, p, timeout);

            // 3. -32002:白名单静默失败,其余重登一次重试
            if (res.error != null && res.error.getType() == ApiError.Type.ACCESS_DENIED) {
                if (isOptionalAclMethod(object, method)) {
                    postFailure(cb, new ApiError(ApiError.Type.PERMISSION_DENIED,
                            object + "." + method + " 无访问权限", -32002));
                    return;
                }
                ApiError loginErr = loginSync(session);
                if (loginErr != null) {
                    postFailure(cb, new ApiError(ApiError.Type.SESSION_EXPIRED, "会话已失效,重新登录失败"));
                    return;
                }
                res = callUbusRawSync(ubusToken, object, method, p, timeout);
            }

            if (res.error != null) {
                postFailure(cb, res.error);
            } else {
                postSuccess(cb, res.data);
            }
        });
    }

    /** -32002 时不触发重登的白名单方法(可单测) */
    static boolean isOptionalAclMethod(String object, String method) {
        return OPTIONAL_ACL_METHODS.contains(object + "." + method);
    }

    private static final class UbusResult {
        final JsonObject data;
        final ApiError error;

        UbusResult(JsonObject data, ApiError error) {
            this.data = data;
            this.error = error;
        }

        static UbusResult ok(JsonObject data) {
            return new UbusResult(data, null);
        }

        static UbusResult fail(ApiError e) {
            return new UbusResult(null, e);
        }
    }

    /** 同步执行一次 ubus JSON-RPC 调用,不含登录/重试逻辑 */
    private UbusResult callUbusRawSync(String session, String object, String method,
                                       JsonObject params, int timeoutSec) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "call");
        JsonArray callParams = new JsonArray();
        callParams.add(session == null ? NULL_SESSION : session);
        callParams.add(object);
        callParams.add(method);
        callParams.add(params);
        body.add("params", callParams);

        Request request = new Request.Builder()
                .url(baseUrl() + "/ubus")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        byte[] bytes;
        try (Response response = clientWithTimeout(timeoutSec).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return UbusResult.fail(new ApiError(ApiError.Type.NETWORK,
                        "HTTP " + response.code()));
            }
            bytes = response.body() != null ? response.body().bytes() : new byte[0];
        } catch (IOException e) {
            return UbusResult.fail(new ApiError(ApiError.Type.NETWORK,
                    "网络错误: " + e.getMessage()));
        }

        // UTF-8 lossy 解码:无效字节替换为 U+FFFD,避免非法 SSID 等导致解析崩溃
        String text = new String(bytes, StandardCharsets.UTF_8);

        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return UbusResult.fail(new ApiError(ApiError.Type.INVALID_RESPONSE, "响应解析失败"));
        }

        // JSON-RPC 层错误
        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            int code = err.has("code") ? err.get("code").getAsInt() : 0;
            if (code == -32002) {
                return UbusResult.fail(new ApiError(ApiError.Type.ACCESS_DENIED,
                        "Access denied", code));
            }
            String msg = err.has("message") ? err.get("message").getAsString() : "RPC 错误";
            return UbusResult.fail(new ApiError(ApiError.Type.UBUS_ERROR, msg, code));
        }

        // result 为 [code] 或 [code, data]
        JsonElement resultEl = root.get("result");
        if (resultEl == null || !resultEl.isJsonArray()) {
            return UbusResult.fail(new ApiError(ApiError.Type.INVALID_RESPONSE, "响应缺少 result"));
        }
        JsonArray result = resultEl.getAsJsonArray();
        if (result.size() == 0) {
            return UbusResult.fail(new ApiError(ApiError.Type.INVALID_RESPONSE, "result 为空"));
        }
        int code = result.get(0).getAsInt();
        if (code != 0) {
            return UbusResult.fail(ApiError.fromUbusCode(code));
        }
        if (result.size() > 1 && result.get(1).isJsonObject()) {
            return UbusResult.ok(result.get(1).getAsJsonObject());
        }
        if (result.size() > 1) {
            // 极少数返回非对象,包一层
            JsonObject wrap = new JsonObject();
            wrap.add("data", result.get(1));
            return UbusResult.ok(wrap);
        }
        return UbusResult.ok(new JsonObject());
    }

    OkHttpClient clientWithTimeout(int timeoutSec) {
        return client.newBuilder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .callTimeout(timeoutSec + 2, TimeUnit.SECONDS)
                .build();
    }

    // =====================================================================
    // LuCI CGI 通道(§2.3,OpenClash 等用)
    // =====================================================================

    /** 取 CGI 用的 sysauth token,无表单 token 时回退 ubus token */
    String cgiSysauthToken() {
        String t = sysauthTokens.get(hostKey());
        return t != null ? t : ubusToken;
    }

    /**
     * GET /cgi-bin/luci&lt;path&gt;,带 sysauth_http cookie,返回原始响应文本。
     * path 例:"/admin/services/openclash/status"
     */
    public void cgiGet(String path, Integer timeoutSec, ApiCallback<String> cb) {
        cgiRequest(path, null, timeoutSec, false, cb);
    }

    /**
     * 带浏览器同款请求头的 GET。
     * OpenClash 的 action 端点会看 Referer / X-Requested-With,缺了会被挡。
     * Cookie 同时带 sysauth 与 sysauth_http —— 与 iOS 一致。
     */
    public void cgiGetAsBrowser(String path, Integer timeoutSec, ApiCallback<String> cb) {
        cgiRequest(path, null, timeoutSec, true, cb);
    }

    /** POST 表单到 CGI 端点 */
    public void cgiPost(String path, Map<String, String> formFields,
                        Integer timeoutSec, ApiCallback<String> cb) {
        FormBody.Builder form = new FormBody.Builder();
        if (formFields != null) {
            for (Map.Entry<String, String> e : formFields.entrySet()) {
                form.add(e.getKey(), e.getValue());
            }
        }
        cgiRequest(path, form.build(), timeoutSec, false, cb);
    }

    private void cgiRequest(String path, RequestBody postBody, Integer timeoutSec,
                            boolean browserHeaders, ApiCallback<String> cb) {
        if (!isConfigured()) {
            postFailure(cb, new ApiError(ApiError.Type.NOT_CONFIGURED, "未配置设备"));
            return;
        }
        int timeout = timeoutSec != null ? timeoutSec : TIMEOUT_DEFAULT_SEC;
        executor.execute(() -> {
            if (!isTokenFresh()) {
                ApiError err = loginSync();
                if (err != null) {
                    postFailure(cb, err);
                    return;
                }
            }
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl() + "/cgi-bin/luci" + path)
                    .header("Cookie", "sysauth_http=" + cgiSysauthToken());
            if (browserHeaders) {
                // 必须在登录之后拼:ubusToken 在上面那步才拿到
                builder.header("Cookie",
                        "sysauth=" + ubusToken + "; sysauth_http=" + cgiSysauthToken());
                builder.header("Accept", "*/*");
                builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                builder.header("Connection", "keep-alive");
                builder.header("X-Requested-With", "XMLHttpRequest");
                builder.header("Referer", baseUrl() + "/cgi-bin/luci" + OC_BASE);
            }
            if (postBody != null) {
                builder.post(postBody);
            }
            try (Response response = clientWithTimeout(timeout).newCall(builder.build()).execute()) {
                byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (response.code() == 403) {
                    postFailure(cb, new ApiError(ApiError.Type.PERMISSION_DENIED, "CGI 访问被拒绝"));
                } else if (!response.isSuccessful() && response.code() != 302) {
                    postFailure(cb, new ApiError(ApiError.Type.NETWORK, "HTTP " + response.code()));
                } else {
                    postSuccess(cb, text);
                }
            } catch (IOException e) {
                postFailure(cb, new ApiError(ApiError.Type.NETWORK, "网络错误: " + e.getMessage()));
            }
        });
    }

    // =====================================================================
    // 系统类 API(§2.4)
    // =====================================================================

    public void getSystemInfo(ApiCallback<JsonObject> cb) {
        makeUbusCall("system", "info", null, null, cb);
    }

    public void getSystemBoard(ApiCallback<JsonObject> cb) {
        makeUbusCall("system", "board", null, null, cb);
    }

    /** 重启路由器 */
    public void reboot(ApiCallback<JsonObject> cb) {
        makeUbusCall("system", "reboot", null, null, cb);
    }

    public void getCpuInfo(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci", "getCPUInfo", null, null, cb);
    }

    public void getCpuUsage(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci", "getCPUUsage", null, null, cb);
    }

    public void getTempInfo(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci", "getTempInfo", null, null, cb);
    }

    public void getMountPoints(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci", "getMountPoints", null, null, cb);
    }

    /** conntrack 连接数:file.read count 与 max,合并为 {"count":n,"max":m} */
    public void getConntrack(ApiCallback<JsonObject> cb) {
        fileRead("/proc/sys/net/netfilter/nf_conntrack_count", new ApiCallback<String>() {
            @Override
            public void onSuccess(String countText) {
                fileRead("/proc/sys/net/netfilter/nf_conntrack_max", new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String maxText) {
                        try {
                            JsonObject out = new JsonObject();
                            out.addProperty("count", Integer.parseInt(countText.trim()));
                            out.addProperty("max", Integer.parseInt(maxText.trim()));
                            cb.onSuccess(out);
                        } catch (NumberFormatException e) {
                            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE,
                                    "conntrack 数据解析失败"));
                        }
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

    // =====================================================================
    // 网络类 API(§2.4)
    // =====================================================================

    public void getNetworkInterfaceDump(ApiCallback<JsonObject> cb) {
        makeUbusCall("network.interface", "dump", null, null, cb);
    }

    public void getNetworkDevices(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci-rpc", "getNetworkDevices", null, null, cb);
    }

    public void getWirelessDevices(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci-rpc", "getWirelessDevices", null, null, cb);
    }

    public void getDHCPLeases(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci-rpc", "getDHCPLeases", null, null, cb);
    }

    public void getHostHints(ApiCallback<JsonObject> cb) {
        makeUbusCall("luci-rpc", "getHostHints", null, null, cb);
    }

    /** iwinfo.assoclist:某无线接口的关联客户端(含 MAC/信号 dBm) */
    public void getWifiAssoclist(String ifname, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("device", ifname);
        makeUbusCall("iwinfo", "assoclist", p, null, cb);
    }

    /**
     * iwinfo.scan(§2.4):同一 device 扫描进行中时合并请求 —— 把 pending 回调排队,
     * 扫描返回时一次性分发给所有等待者(复刻 iOS 请求合并去重)。扫描较慢用 30s 超时。
     */
    private final Map<String, List<ApiCallback<JsonObject>>> pendingScans = new ConcurrentHashMap<>();

    public void scanWifi(String device, ApiCallback<JsonObject> cb) {
        synchronized (pendingScans) {
            List<ApiCallback<JsonObject>> waiting = pendingScans.get(device);
            if (waiting != null) {
                waiting.add(cb);     // 已有扫描在途,排队等结果
                return;
            }
            List<ApiCallback<JsonObject>> list = new ArrayList<>();
            list.add(cb);
            pendingScans.put(device, list);
        }
        JsonObject p = new JsonObject();
        p.addProperty("device", device);
        makeUbusCall("iwinfo", "scan", p, 30, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                for (ApiCallback<JsonObject> c : drainScans(device)) {
                    c.onSuccess(result);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                for (ApiCallback<JsonObject> c : drainScans(device)) {
                    c.onFailure(error);
                }
            }
        });
    }

    private List<ApiCallback<JsonObject>> drainScans(String device) {
        synchronized (pendingScans) {
            List<ApiCallback<JsonObject>> list = pendingScans.remove(device);
            return list != null ? list : new ArrayList<>();
        }
    }

    // =====================================================================
    // 黑名单(防火墙 MAC 封禁)
    // =====================================================================

    /** 被本 App 封禁的防火墙规则名称前缀,用于识别与列举 */
    public static final String BLOCK_RULE_PREFIX = "WrtHub-Block-";

    /**
     * 封禁设备:加一条 DROP 防火墙规则,commit 后重载防火墙。
     *
     * 分步写,与 iOS blockDevice 一致:
     *  1. 先读整份 firewall 配置,已经有同 MAC 的规则就直接返回成功(别加重复规则);
     *  2. `uci add` 建空 section —— **不带 values**。部分固件的 rpcd 对
     *     add 携带 values 支持不全,会返回成功但字段没写进去,或者直接报错;
     *  3. 逐个 `uci set` 写属性,补上 dest=* 与 proto=all ——
     *     少了这两项 fw3/fw4 会把规则当成 input 规则,拦不住转发流量;
     *  4. commit + reload。
     */
    public void blockDevice(String mac, String displayName, ApiCallback<Void> cb) {
        if (mac == null || mac.isEmpty()) {
            postFailure(cb, new ApiError(ApiError.Type.INVALID_RESPONSE, "设备没有 MAC 地址"));
            return;
        }
        String macUpper = mac.toUpperCase(Locale.ROOT);
        uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject config) {
                if (hasBlockRule(config, macUpper)) {
                    postSuccess(cb, null);      // 已经封过了
                    return;
                }
                createBlockRule(macUpper, displayName, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                // 读不到配置也别卡死,直接尝试新建
                createBlockRule(macUpper, displayName, cb);
            }
        });
    }

    /** uci 的 src_mac 可能是字符串,也可能是 list;统一取第一个值 */
    public static String firstSrcMac(JsonObject rule) {
        if (!rule.has("src_mac") || rule.get("src_mac").isJsonNull()) return null;
        JsonElement el = rule.get("src_mac");
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return arr.size() > 0 ? arr.get(0).getAsString() : null;
        }
        return el.getAsString();
    }

    /** firewall 配置里是否已有针对该 MAC 的封禁规则 */
    static boolean hasBlockRule(JsonObject config, String macUpper) {
        JsonObject values = config.has("values") && config.get("values").isJsonObject()
                ? config.getAsJsonObject("values") : config;
        for (String section : values.keySet()) {
            if (!values.get(section).isJsonObject()) continue;
            JsonObject rule = values.getAsJsonObject(section);
            if (!rule.has("src_mac")) continue;
            JsonElement srcMac = rule.get("src_mac");
            if (srcMac.isJsonArray()) {
                for (JsonElement e : srcMac.getAsJsonArray()) {
                    if (macUpper.equalsIgnoreCase(e.getAsString())) return true;
                }
            } else if (macUpper.equalsIgnoreCase(srcMac.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规则名只留字母数字和 -_ —— fw4 会把 name 写进 nftables 的注释/链名,
     * 带空格或中文会让 reload 失败(uci 那一步倒是不报错,坑就在这)。
     * 清理后为空就退回用 MAC。设备名在黑名单页会按 MAC 从租约表里重新查,不靠这个。
     */
    static String blockRuleName(String macUpper, String displayName) {
        StringBuilder safe = new StringBuilder();
        if (displayName != null) {
            for (char c : displayName.toCharArray()) {
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                    safe.append(c);
                }
            }
        }
        if (safe.length() == 0) {
            safe.append(macUpper.replace(':', '_'));
        }
        if (safe.length() > 40) safe.setLength(40);
        return BLOCK_RULE_PREFIX + safe;
    }

    private void createBlockRule(String macUpper, String displayName, ApiCallback<Void> cb) {
        uciAdd("firewall", "rule", null, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String section = result.has("section") && !result.get("section").isJsonNull()
                        ? result.get("section").getAsString() : null;
                if (section == null) {
                    postFailure(cb, new ApiError(ApiError.Type.INVALID_RESPONSE,
                            "无法获取新建规则的标识符"));
                    return;
                }
                String[][] settings = {
                        {"name", blockRuleName(macUpper, displayName)},
                        {"src", "lan"},
                        {"dest", "*"},
                        {"proto", "all"},
                        {"src_mac", macUpper},
                        {"target", "DROP"},
                        {"enabled", "1"},
                };
                setBlockRuleOptions(section, settings, 0, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(step("uci add rule", error));
            }
        });
    }

    /** 失败时带上出错的步骤,不然只看到一句「操作失败」没法排查 */
    private static ApiError step(String what, ApiError error) {
        return new ApiError(error.getType(),
                what + ": " + error.getMessage(), error.getUbusCode());
    }

    /** 逐项写入;任一项失败就 revert 掉这次未提交的改动,别留半条规则 */
    private void setBlockRuleOptions(String section, String[][] settings, int index,
                                     ApiCallback<Void> cb) {
        if (index >= settings.length) {
            commitAndReloadFirewall(cb);
            return;
        }
        JsonObject values = new JsonObject();
        values.addProperty(settings[index][0], settings[index][1]);
        uciSet("firewall", section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                setBlockRuleOptions(section, settings, index + 1, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                ApiError wrapped = step("uci set " + settings[index][0], error);
                uciRevert("firewall", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        cb.onFailure(wrapped);
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        cb.onFailure(wrapped);
                    }
                });
            }
        });
    }

    /** 解封:删除指定 firewall section,再 apply/commit 重载 */
    public void unblockDevice(String section, ApiCallback<Void> cb) {
        if (section == null || section.isEmpty()) {
            postFailure(cb, new ApiError(ApiError.Type.CONFIG_NOT_FOUND, "找不到对应的防火墙规则"));
            return;
        }
        uciDelete("firewall", section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                commitAndReloadFirewall(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(step("uci delete " + section, error));
            }
        });
    }

    // =====================================================================
    // 端口转发 / 流量规则(防火墙 redirect / rule CRUD,§2.4)
    // =====================================================================

    /** 新增防火墙 section(redirect=端口转发,rule=流量规则),commit 后重启防火墙 */
    public void addFirewallSection(String type, JsonObject values, ApiCallback<Void> cb) {
        uciAdd("firewall", type, null, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                commitAndReloadFirewall(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 删除防火墙 section,commit 后重启防火墙 */
    public void deleteFirewallSection(String section, ApiCallback<Void> cb) {
        uciDelete("firewall", section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                commitAndReloadFirewall(cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    // =====================================================================
    // OpenClash(§2.3,LuCI CGI 通道)
    // =====================================================================

    private static final String OC_BASE = "/admin/services/openclash";

    public void openclashStatus(ApiCallback<JsonObject> cb) {
        cgiGetJson(OC_BASE + "/status", cb);
    }

    public void openclashRuleMode(ApiCallback<JsonObject> cb) {
        cgiGetJson(OC_BASE + "/rule_mode", cb);
    }

    /**
     * 切换代理模式。
     * **GET + 查询参数 rule_mode**,与网页端/iOS 一致 —— 原来发的是
     * POST 表单 mode=xxx,端点根本不认,所以一直「操作失败」。
     */
    public void openclashSwitchRuleMode(String mode, ApiCallback<JsonObject> cb) {
        String path = OC_BASE + "/switch_rule_mode?rule_mode=" + urlEncode(mode)
                + "&" + System.currentTimeMillis();
        cgiGetJson(path, cb);
    }

    /**
     * 启停走的是 **action 端点**(?action=start / ?action=stop),
     * 不是 /startclash、/closeclash —— 后者在 OpenClash 里不存在。
     * 只认 HTTP 200,响应体不一定是 JSON,别去解析它。
     */
    public void openclashStart(ApiCallback<JsonObject> cb) {
        openclashAction("start", cb);
    }

    public void openclashStop(ApiCallback<JsonObject> cb) {
        openclashAction("stop", cb);
    }

    private void openclashAction(String action, ApiCallback<JsonObject> cb) {
        String path = OC_BASE + "/action?action=" + action + "&" + System.currentTimeMillis();
        // 启停要跑脚本,给足 30 秒
        cgiGetAsBrowser(path, 30, new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                cb.onSuccess(new JsonObject());
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    public void openclashReload(ApiCallback<JsonObject> cb) {
        cgiGetJson(OC_BASE + "/reload_config", cb);
    }

    /** 网站连通性检测(返回各目标可达性) */
    public void openclashWebsiteCheck(ApiCallback<JsonObject> cb) {
        cgiGetJson(OC_BASE + "/website_check", cb);
    }

    /** 出口 IP 检测 */
    public void openclashMyipCheck(ApiCallback<JsonObject> cb) {
        cgiGetJson(OC_BASE + "/myip_check", cb);
    }

    /** OpenClash 的 CGI 端点统一用浏览器同款请求头,与 iOS 一致 */
    private void cgiGetJson(String path, ApiCallback<JsonObject> cb) {
        cgiGetAsBrowser(path, 15, jsonWrap(cb));
    }

    /** 把 CGI 的文本响应(可能夹杂空白/非法字节)解析为 JsonObject */
    private ApiCallback<String> jsonWrap(ApiCallback<JsonObject> cb) {
        return new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                try {
                    String t = text != null ? text.trim() : "";
                    int start = t.indexOf('{');
                    int end = t.lastIndexOf('}');
                    if (start >= 0 && end > start) {
                        t = t.substring(start, end + 1);
                    }
                    cb.onSuccess(JsonParser.parseString(t).getAsJsonObject());
                } catch (Exception e) {
                    cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "OpenClash 响应解析失败"));
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        };
    }

    /**
     * 提交并应用防火墙改动。
     *
     * **先 `uci apply`,失败才退回 `uci commit` + reload**(与 iOS commitAndReloadFirewall 一致)。
     * 不少固件的 rpcd ACL 只放开了 uci.apply,直接调 uci.commit 会 -32002 Access denied ——
     * set 全成功、最后一步挂掉,就是这么来的。apply 本身会提交并触发重载。
     */
    private void commitAndReloadFirewall(ApiCallback<Void> cb) {
        uciApply("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(null);
            }

            @Override
            public void onFailure(ApiError applyError) {
                // 老固件没有 uci.apply,退回 commit
                uciCommit("firewall", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        reloadFirewall(cb);
                    }

                    @Override
                    public void onFailure(ApiError commitError) {
                        // 两条路都不通,把更可能有用的那条错误报出去
                        cb.onFailure(step("uci apply/commit firewall", commitError));
                    }
                });
            }
        });
    }

    /** 重载防火墙:先 fw3,不行再走 init.d。都失败也不算致命 —— 配置已经提交了 */
    private void reloadFirewall(ApiCallback<Void> cb) {
        fileExec("fw3", new String[]{"reload"}, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                cb.onSuccess(null);
            }

            @Override
            public void onFailure(ApiError error) {
                fileExec("/etc/init.d/firewall", new String[]{"reload"}, null,
                        new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject r) {
                                cb.onSuccess(null);
                            }

                            @Override
                            public void onFailure(ApiError e) {
                                cb.onSuccess(null);
                            }
                        });
            }
        });
    }

    // =====================================================================
    // uci 通用读写(WiFi/防火墙/Samba 等上层功能共用)
    // =====================================================================

    /** uci get 整份配置(iOS 修过:不存在的 section 用整配置判断,不能单独 get) */
    public void uciGetConfig(String config, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        makeUbusCall("uci", "get", p, null, cb);
    }

    public void uciGet(String config, String section, String option, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        if (section != null) p.addProperty("section", section);
        if (option != null) p.addProperty("option", option);
        makeUbusCall("uci", "get", p, null, cb);
    }

    public void uciSet(String config, String section, JsonObject values, ApiCallback<JsonObject> cb) {
        uciSetTyped(config, section, null, values, cb);
    }

    /** uci set,可带 type —— 命名 section 不存在时用 type 创建(如 network.wwan=interface) */
    public void uciSetTyped(String config, String section, String type,
                            JsonObject values, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        p.addProperty("section", section);
        if (type != null) p.addProperty("type", type);
        p.add("values", values);
        makeUbusCall("uci", "set", p, null, cb);
    }

    public void uciAdd(String config, String type, String name, JsonObject values,
                       ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        p.addProperty("type", type);
        if (name != null) p.addProperty("name", name);
        if (values != null) p.add("values", values);
        makeUbusCall("uci", "add", p, null, cb);
    }

    public void uciDelete(String config, String section, String option, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        p.addProperty("section", section);
        if (option != null) p.addProperty("option", option);
        makeUbusCall("uci", "delete", p, null, cb);
    }

    /** 丢弃某份配置里尚未 commit 的改动(写到一半失败时用,别留残缺 section) */
    public void uciRevert(String config, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        makeUbusCall("uci", "revert", p, null, cb);
    }

    public void uciCommit(String config, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        makeUbusCall("uci", "commit", p, null, cb);
    }

    /**
     * uci apply —— 提交并触发 /sbin/reload_config,让 procd reload trigger 重载服务。
     * 部分插件(eqos/samba4/vsftpd)的 rpcd ACL 只白名单了 apply 而没有 commit,
     * 直接 commit 会 -32002,所以这些配置一律走 apply。
     */
    public void uciApply(String config, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("config", config);
        // 明确关掉回滚:rollback=true 时 rpcd 会等一个 uci.confirm,
        // 超时没等到就把刚提交的改动整个撤回 —— 看着成功了,过几十秒又变回去。
        p.addProperty("rollback", false);
        makeUbusCall("uci", "apply", p, null, cb);
    }

    /**
     * 先 apply,失败再退回 commit。
     *
     * 写配置一律走这个,不要直接调 uciCommit —— 不少固件的 rpcd ACL 只放开了
     * uci.apply,直接 commit 会 -32002 Access denied(前面的 set 全成功、
     * 最后一步挂掉)。老固件没有 uci.apply,才需要 commit 兜底。
     */
    public void uciApplyOrCommit(String config, ApiCallback<JsonObject> cb) {
        uciApply(config, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(result);
            }

            @Override
            public void onFailure(ApiError applyError) {
                uciCommit(config, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        cb.onSuccess(result);
                    }

                    @Override
                    public void onFailure(ApiError commitError) {
                        // 两条路都堵死时,把两个原因都报出来 ——
                        // 只说 commit 失败会让人以为只差一个权限,其实 apply 也没过
                        cb.onFailure(new ApiError(commitError.getType(),
                                "apply: " + applyError.getMessage()
                                        + " / commit: " + commitError.getMessage(),
                                commitError.getUbusCode()));
                    }
                });
            }
        });
    }

    // =====================================================================
    // 插件安装检测(file.stat 看 init 脚本在不在,对应 iOS check*Installed)
    // =====================================================================

    public void fileStat(String path, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("path", path);
        makeUbusCall("file", "stat", p, null, cb);
    }

    /** /etc/init.d/&lt;name&gt; 是否存在。stat 失败一律视为未安装,不当错误抛出。 */
    public void checkInitScriptExists(String name, ApiCallback<Boolean> cb) {
        fileStat("/etc/init.d/" + name, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String type = result.has("type") ? result.get("type").getAsString() : "";
                cb.onSuccess("file".equals(type) || "link".equals(type) || "symlink".equals(type));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onSuccess(false);
            }
        });
    }

    /**
     * 尽力而为地对 init 脚本执行动作:先试 LuCI 的 luci.setInitAction(网页端同款通道),
     * 不通再退回 file.exec。两条都可能被 ACL 拒 —— 拒了也不算失败,
     * 因为 uci.apply 触发的 reload_config 通常已经让配置生效(对应 iOS bestEffortInitAction)。
     */
    /**
     * ubus service.list —— 查某个 procd 服务的运行状态。
     * 响应形如 {"<name>":{"instances":{"instance1":{"running":true,...}}}}
     */
    public void serviceList(String name, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        makeUbusCall("service", "list", p, null, cb);
    }

    /**
     * service.list 的响应里该服务有没有在跑。
     * LuCI 只看 instance1,这里遍历所有实例 —— 多实例服务不该因为名字不叫
     * instance1 就被判成没运行。
     */
    public static boolean serviceIsRunning(JsonObject listResult, String name) {
        if (listResult == null || !listResult.has(name)
                || !listResult.get(name).isJsonObject()) {
            return false;
        }
        JsonObject service = listResult.getAsJsonObject(name);
        if (!service.has("instances") || !service.get("instances").isJsonObject()) {
            return false;
        }
        JsonObject instances = service.getAsJsonObject("instances");
        for (String key : instances.keySet()) {
            if (!instances.get(key).isJsonObject()) continue;
            JsonObject instance = instances.getAsJsonObject(key);
            try {
                if (instance.has("running") && instance.get("running").getAsBoolean()) {
                    return true;
                }
            } catch (Exception ignored) {
                // running 字段类型异常,当作没在跑
            }
        }
        return false;
    }

    public void bestEffortInitAction(String name, String action, Runnable then) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("action", action);
        makeUbusCall("luci", "setInitAction", p, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                then.run();
            }

            @Override
            public void onFailure(ApiError error) {
                fileExec("/etc/init.d/" + name, new String[]{action}, null,
                        new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject r) {
                                then.run();
                            }

                            @Override
                            public void onFailure(ApiError e) {
                                then.run();
                            }
                        });
            }
        });
    }

    // =====================================================================
    // 文件与进程(file.*)
    // =====================================================================

    public void fileRead(String path, ApiCallback<String> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("path", path);
        makeUbusCall("file", "read", p, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonElement data = result.get("data");
                cb.onSuccess(data != null ? data.getAsString() : "");
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** init.d 服务控制:/etc/init.d/&lt;service&gt; &lt;action&gt;(start/stop/restart/reload/enabled) */
    public void initdControl(String service, String action, ApiCallback<JsonObject> cb) {
        fileExec("/etc/init.d/" + service, new String[]{action}, null, cb);
    }

    /** file.write:写文件(append 为 true 时追加) */
    public void fileWrite(String path, String data, boolean append, ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("path", path);
        p.addProperty("data", data);
        if (append) p.addProperty("append", true);
        makeUbusCall("file", "write", p, null, cb);
    }

    /** file.exec,返回 {"code":n,"stdout":"...","stderr":...} */
    public void fileExec(String command, String[] args, Integer timeoutSec,
                         ApiCallback<JsonObject> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("command", command);
        JsonArray params = new JsonArray();
        if (args != null) {
            for (String a : args) {
                params.add(a);
            }
        }
        p.add("params", params);
        makeUbusCall("file", "exec", p, timeoutSec, cb);
    }

    // =====================================================================
    // 日志(双路:file.exec 优先,权限拒绝回退 ubus log.read)
    // =====================================================================

    public void getSystemLogs(ApiCallback<String> cb) {
        // 参数必须带上:不少固件的 rpcd ACL 白名单登记的是完整命令行
        // (`/sbin/logread -e ^`),只传命令名匹配不上,直接 code 6
        execLogWithFallback("/sbin/logread", new String[]{"-e", "^"}, false, cb);
    }

    public void getKernelLogs(ApiCallback<String> cb) {
        execLogWithFallback("/bin/dmesg", new String[]{"-r"}, true, cb);
    }

    /**
     * 两条通道,与 iOS fetchLog 一致:
     *  1) file.exec 跑 logread/dmesg —— ImmortalWrt 等固件把这两条命令加进了白名单;
     *  2) **任何**失败都回退 ubus log.read —— 部分固件(如 GL.iNet)反过来只放开 log 对象。
     * 顺序不能反:file.exec 失败是 code 6(无害),log.read 失败是 -32002,
     * 先跑 log.read 会在 file.exec 可用的设备上白白触发一次重登。
     */
    private void execLogWithFallback(String command, String[] args, boolean kernelOnly,
                                     ApiCallback<String> cb) {
        fileExec(command, args, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                // exec 成功就采用,哪怕 stdout 是空的 —— 再去试 log.read 只会多一次 -32002
                JsonElement stdout = result.get("stdout");
                cb.onSuccess(stdout != null && !stdout.isJsonNull() ? stdout.getAsString() : "");
            }

            @Override
            public void onFailure(ApiError error) {
                readUbusLog(kernelOnly, cb);
            }
        });
    }

    private void readUbusLog(boolean kernelOnly, ApiCallback<String> cb) {
        JsonObject p = new JsonObject();
        p.addProperty("lines", 1000);
        p.addProperty("stream", false);
        p.addProperty("oneshot", true);
        makeUbusCall("log", "read", p, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(formatLogRead(result, kernelOnly));
            }

            @Override
            public void onFailure(ApiError error) {
                if (error.getType() == ApiError.Type.PERMISSION_DENIED
                        || error.getType() == ApiError.Type.ACCESS_DENIED) {
                    cb.onFailure(new ApiError(ApiError.Type.PERMISSION_DENIED,
                            "当前账号无日志读取权限,请在路由器上检查 ACL 配置 / " +
                                    "No permission to read logs. Check ACL config on the router."));
                } else {
                    cb.onFailure(error);
                }
            }
        });
    }

    /** syslog priority 低三位对应的级别名 */
    private static final String[] LOG_LEVELS = {
            "emerg", "alert", "crit", "error", "warn", "notice", "info", "debug"};

    /**
     * 把 log.read 的结构化条目([{msg,priority,source,time}])拼成 logread 那样的文本。
     * kernelOnly 时只留 source==0(内核)的条目;内核消息自带 [秒数] 前缀,不再加时间。
     */
    static String formatLogRead(JsonObject result, boolean kernelOnly) {
        JsonElement logEl = result.get("log");
        if (logEl == null || !logEl.isJsonArray()) return "";
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : logEl.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject entry = e.getAsJsonObject();
            int source = intOf(entry, "source", -1);
            if (kernelOnly && source != 0) continue;

            JsonElement msgEl = entry.get("msg");
            String msg = msgEl != null && !msgEl.isJsonNull() ? msgEl.getAsString().trim() : "";
            if (msg.isEmpty()) continue;

            if (!kernelOnly) {
                long time = longOf(entry, "time", 0);
                if (time > 0) {
                    // time 是毫秒;个别固件给的是秒,数量级明显小就按秒处理
                    long millis = time < 100000000000L ? time * 1000 : time;
                    sb.append(fmt.format(new java.util.Date(millis))).append(' ');
                }
                int priority = intOf(entry, "priority", -1);
                if (priority >= 0 && priority < LOG_LEVELS.length) {
                    sb.append('[').append(LOG_LEVELS[priority]).append("] ");
                }
            }
            sb.append(msg).append('\n');
        }
        return sb.toString();
    }

    private static int intOf(JsonObject o, String key, int def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long longOf(JsonObject o, String key, long def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    // =====================================================================
    // 诊断(ping/traceroute/nslookup,60s 超时,参数与 LuCI 一致)
    // =====================================================================

    public enum DiagnosticType { PING, TRACEROUTE, NSLOOKUP }

    /**
     * 命令名**不带绝对路径**,与 iOS 一致。
     *
     * rpcd 的 file exec ACL 是按命令字符串原样匹配的,白名单里登记的就是
     * ping / traceroute / nslookup 这种裸名字。写死 /usr/bin/traceroute
     * 既可能匹配不上 ACL,也可能在别的固件上根本不是那个路径
     * (busybox 的 traceroute 有的在 /bin,有的在 /usr/bin)。
     */
    static String diagnosticCommand(DiagnosticType type) {
        switch (type) {
            case PING:
                return "ping";
            case TRACEROUTE:
                return "traceroute";
            default:
                return "nslookup";
        }
    }

    /** 参数与 iOS NetworkDiagnosticsViewController 逐项对齐 */
    static String[] diagnosticArgs(DiagnosticType type, String target, boolean ipv6) {
        String family = ipv6 ? "-6" : "-4";
        switch (type) {
            case PING:
                return new String[]{family, "-c", "5", "-W", "1", target};
            case TRACEROUTE:
                // -m 20 限制最大跳数,不然默认 30 跳、不可达时要干等很久
                return new String[]{family, "-q", "1", "-w", "1", "-n", "-m", "20", target};
            default:
                return new String[]{target};
        }
    }

    public void runDiagnostic(DiagnosticType type, String target, boolean ipv6,
                              ApiCallback<String> cb) {
        fileExec(diagnosticCommand(type), diagnosticArgs(type, target, ipv6),
                TIMEOUT_DIAGNOSTIC_SEC, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(diagnosticOutput(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** stdout + stderr;都空时带上退出码,总比交一片空白强 */
    static String diagnosticOutput(JsonObject result) {
        String stdout = strOf(result, "stdout");
        String stderr = strOf(result, "stderr");
        StringBuilder sb = new StringBuilder(stdout);
        if (!stderr.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(stderr);
        }
        if (sb.length() == 0) {
            int code = intOf(result, "code", -1);
            sb.append("命令无输出 (退出码 ").append(code).append(')');
        }
        return sb.toString();
    }

    private static String strOf(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // =====================================================================
    // 回调投递(主线程)
    // =====================================================================

    <T> void postSuccess(ApiCallback<T> cb, T value) {
        callbackExecutor().execute(() -> cb.onSuccess(value));
    }

    <T> void postFailure(ApiCallback<T> cb, ApiError error) {
        callbackExecutor().execute(() -> cb.onFailure(error));
    }

    private Executor callbackExecutor() {
        Executor e = callbackExecutor;
        if (e == null) {
            synchronized (this) {
                if (callbackExecutor == null) {
                    callbackExecutor = new MainThreadExecutor();
                }
                e = callbackExecutor;
            }
        }
        return e;
    }

    /** 默认回调线程:Android 主线程。延迟加载以便 JVM 单测可替换。 */
    private static final class MainThreadExecutor implements Executor {
        private final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}