package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 登录失败的原因分类。
 * 原来 UBUS_ERROR(所有非 4/6 状态码 + JSON-RPC 层错误的兜底类型)也被当成
 * 「用户名或密码错误」,于是后台静默重登撞上路由器繁忙时,界面会冒出一句
 * 「登录失败:用户名或密码错误」—— 密码其实没问题。
 */
public class LoginErrorTest {

    @Test
    public void onlyCodeSixMeansWrongCredentials() {
        ApiError e = OpenWrtApi.loginError(ApiError.fromUbusCode(6));
        assertEquals(ApiError.Type.AUTH_FAILED, e.getType());
        assertTrue(e.getMessage(), e.getMessage().contains("密码"));
    }

    @Test
    public void timeoutIsNotACredentialProblem() {
        ApiError e = OpenWrtApi.loginError(ApiError.fromUbusCode(7));
        assertFalse("超时不该说成密码错误", e.getType() == ApiError.Type.AUTH_FAILED);
        assertEquals(ApiError.Type.NETWORK, e.getType());
    }

    @Test
    public void missingLoginEndpointIsNotACredentialProblem() {
        ApiError e = OpenWrtApi.loginError(ApiError.fromUbusCode(4));
        assertFalse(e.getType() == ApiError.Type.AUTH_FAILED);
    }

    @Test
    public void unknownCodesKeepTheirNumber() {
        ApiError e = OpenWrtApi.loginError(ApiError.fromUbusCode(2));
        assertFalse(e.getType() == ApiError.Type.AUTH_FAILED);
        assertTrue(e.getMessage(), e.getMessage().contains("2"));
    }

    @Test
    public void networkErrorsPassThroughUnchanged() {
        ApiError raw = new ApiError(ApiError.Type.NETWORK, "网络错误: timeout");
        assertSame(raw, OpenWrtApi.loginError(raw));
    }

    @Test
    public void everythingButWrongCredentialsIsWorthRetrying() {
        assertTrue(OpenWrtApi.loginFailureIsTransient(
                new ApiError(ApiError.Type.NETWORK, "timeout")));
        assertTrue(OpenWrtApi.loginFailureIsTransient(
                new ApiError(ApiError.Type.UBUS_ERROR, "busy", 7)));
        assertFalse("密码错了重试多少次都没用",
                OpenWrtApi.loginFailureIsTransient(
                        new ApiError(ApiError.Type.AUTH_FAILED, "用户名或密码错误", 6)));
    }
}
