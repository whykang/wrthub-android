package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

/**
 * 网络层回归测试,对应文档 §2 中 iOS 修过的坑:
 * - 双通道登录、sysauth cookie 解析
 * - 表单登录失败不阻塞 ubus 登录
 * - ubus code 4/6 错误映射
 * - -32002 白名单方法不触发重登;非白名单重登一次并重试
 * - 非法 UTF-8 响应不崩溃
 */
public class OpenWrtApiTest {

    private MockWebServer server;
    private OpenWrtApi api;

    private static final String LOGIN_OK = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
            + "[0,{\"ubus_rpc_session\":\"deadbeef1234\",\"expires\":300}]}";

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        api = new OpenWrtApi();
        api.setCallbackExecutor(Runnable::run);
        api.configure(server.getHostName(), server.getPort(), false, "root", "password");
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private MockResponse ubusResult(String resultJson) {
        return new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + resultJson + "}");
    }

    private MockResponse formLogin302() {
        return new MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "sysauth_http=abc123token; path=/cgi-bin/luci; HttpOnly")
                .addHeader("Location", "/cgi-bin/luci/admin");
    }

    /** 同步等待回调结果 */
    private static final class Result<T> {
        T value;
        ApiError error;
    }

    private <T> Result<T> await(java.util.function.Consumer<ApiCallback<T>> call)
            throws InterruptedException {
        Result<T> result = new Result<>();
        CountDownLatch latch = new CountDownLatch(1);
        call.accept(new ApiCallback<T>() {
            @Override
            public void onSuccess(T v) {
                result.value = v;
                latch.countDown();
            }

            @Override
            public void onFailure(ApiError e) {
                result.error = e;
                latch.countDown();
            }
        });
        assertTrue("回调超时", latch.await(10, TimeUnit.SECONDS));
        return result;
    }

    // ---- 登录 ----

    @Test
    public void login_savesBothTokens() throws Exception {
        server.enqueue(formLogin302());     // 表单登录
        server.enqueue(new MockResponse().setBody(LOGIN_OK));  // ubus 登录

        Result<Void> r = await(cb -> api.login(cb));

        assertNull(r.error);
        assertTrue(api.isTokenFresh());
        assertEquals("abc123token", api.cgiSysauthToken());
        assertEquals(2, server.getRequestCount());
        // 第一条请求应为 CGI 表单
        assertEquals("/cgi-bin/luci/", server.takeRequest().getPath());
        assertEquals("/ubus", server.takeRequest().getPath());
    }

    @Test
    public void login_formFailureDoesNotBlockUbusLogin() throws Exception {
        // 表单返回 403(无 CGI 表单的固件),ubus 登录仍应成功
        server.enqueue(new MockResponse().setResponseCode(403));
        server.enqueue(new MockResponse().setBody(LOGIN_OK));

        Result<Void> r = await(cb -> api.login(cb));

        assertNull(r.error);
        assertTrue(api.isTokenFresh());
        // 表单 token 不存在时,CGI 回退用 ubus token
        assertEquals("deadbeef1234", api.cgiSysauthToken());
    }

    @Test
    public void login_wrongPassword_mapsToAuthFailed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403));
        // ubus 登录失败:result [6](权限拒绝)
        server.enqueue(ubusResult("[6]"));

        Result<Void> r = await(cb -> api.login(cb));

        assertNotNull(r.error);
        assertEquals(ApiError.Type.AUTH_FAILED, r.error.getType());
        assertFalse(api.isTokenFresh());
    }

    // ---- makeUbusCall 错误映射 ----

    @Test
    public void ubusCall_code4_mapsToConfigNotFound() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        server.enqueue(ubusResult("[4]"));

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("uci", "get", null, null, cb));

        assertNotNull(r.error);
        assertEquals(ApiError.Type.CONFIG_NOT_FOUND, r.error.getType());
    }

    @Test
    public void ubusCall_code6_mapsToPermissionDenied() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        server.enqueue(ubusResult("[6]"));

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("file", "exec", null, null, cb));

        assertNotNull(r.error);
        assertEquals(ApiError.Type.PERMISSION_DENIED, r.error.getType());
    }

    @Test
    public void ubusCall_success_returnsData() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        server.enqueue(ubusResult("[0,{\"hostname\":\"OpenWrt\"}]"));

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("system", "board", null, null, cb));

        assertNull(r.error);
        assertEquals("OpenWrt", r.value.get("hostname").getAsString());
    }

    // ---- -32002 处理(重登风暴 bug 回归) ----

    private static final String ACCESS_DENIED = "{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"error\":{\"code\":-32002,\"message\":\"Access denied\"}}";

    @Test
    public void accessDenied_whitelistedMethod_doesNotRelogin() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        server.enqueue(new MockResponse().setBody(ACCESS_DENIED));

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("luci", "getCPUInfo", null, null, cb));

        assertNotNull(r.error);
        assertEquals(ApiError.Type.PERMISSION_DENIED, r.error.getType());
        // 关键:总请求数 3(表单+登录+调用),没有触发重新登录
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void accessDenied_normalMethod_reloginsOnceAndRetries() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        server.enqueue(new MockResponse().setBody(ACCESS_DENIED));   // 首次调用被拒
        server.enqueue(formLogin302());                              // 重登:表单
        server.enqueue(new MockResponse().setBody(LOGIN_OK));        // 重登:ubus
        server.enqueue(ubusResult("[0,{\"uptime\":12345}]"));        // 重试成功

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("system", "info", null, null, cb));

        assertNull(r.error);
        assertEquals(12345, r.value.get("uptime").getAsInt());
        assertEquals(6, server.getRequestCount());
    }

    // ---- UTF-8 清洗(非法 SSID 崩溃回归) ----

    @Test
    public void invalidUtf8InResponse_doesNotCrash() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));

        // 构造含非法 UTF-8 字节(0xC3 0x28)的 SSID
        Buffer buffer = new Buffer();
        buffer.writeUtf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[0,{\"ssid\":\"bad-");
        buffer.write(new byte[]{(byte) 0xC3, (byte) 0x28});
        buffer.writeUtf8("\"}]}");
        server.enqueue(new MockResponse().setBody(buffer));

        Result<JsonObject> r = await(cb ->
                api.makeUbusCall("iwinfo", "scan", null, null, cb));

        assertNull(r.error);
        // 非法字节被替换为 U+FFFD,SSID 仍可读取
        assertTrue(r.value.get("ssid").getAsString().startsWith("bad-"));
    }

    // ---- 纯逻辑 ----

    @Test
    public void optionalAclWhitelist_matchesDocList() {
        assertTrue(OpenWrtApi.isOptionalAclMethod("luci", "getCPUInfo"));
        assertTrue(OpenWrtApi.isOptionalAclMethod("luci", "getCPUUsage"));
        assertTrue(OpenWrtApi.isOptionalAclMethod("luci", "getTempInfo"));
        assertTrue(OpenWrtApi.isOptionalAclMethod("network", "reload"));
        assertFalse(OpenWrtApi.isOptionalAclMethod("system", "info"));
        assertFalse(OpenWrtApi.isOptionalAclMethod("luci-rpc", "getDHCPLeases"));
    }

    @Test
    public void extractCookie_parsesSetCookieHeader() {
        assertEquals("tok123", OpenWrtApi.extractCookie(
                "sysauth_http=tok123; path=/cgi-bin/luci; HttpOnly", "sysauth_http"));
        assertNull(OpenWrtApi.extractCookie(
                "other=v; path=/", "sysauth_http"));
        assertNull(OpenWrtApi.extractCookie(null, "sysauth_http"));
    }

    @Test
    public void configure_clearsPreviousSession() throws Exception {
        server.enqueue(formLogin302());
        server.enqueue(new MockResponse().setBody(LOGIN_OK));
        Result<Void> r = await(cb -> api.login(cb));
        assertNull(r.error);
        assertTrue(api.isTokenFresh());

        // 切换设备:token 与 cookie 必须作废(设备切换串号 bug 回归)
        api.configure(server.getHostName(), server.getPort(), false, "root", "other");
        assertFalse(api.isTokenFresh());
        assertNull(api.cgiSysauthToken());
    }
}