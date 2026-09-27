package com.whykangkang.wrthub.api;

/**
 * API 错误,对应 iOS 的 enum APIError。
 * ubus 返回码映射:4=配置项未找到、6=权限被拒绝、-32002=Access denied。
 */
public final class ApiError extends Exception {

    public enum Type {
        INVALID_URL,        // 地址不合法
        NETWORK,            // 网络失败(连接/超时)
        INVALID_RESPONSE,   // 响应无法解析
        AUTH_FAILED,        // 用户名或密码错误
        SESSION_EXPIRED,    // 会话失效(重登后仍失败)
        PERMISSION_DENIED,  // ubus code 6
        CONFIG_NOT_FOUND,   // ubus code 4
        ACCESS_DENIED,      // JSON-RPC -32002
        UBUS_ERROR,         // 其他非零 ubus code
        NOT_CONFIGURED      // 未配置设备就发起调用
    }

    private final Type type;
    private final int ubusCode;

    public ApiError(Type type, String message) {
        this(type, message, 0);
    }

    public ApiError(Type type, String message, int ubusCode) {
        super(message);
        this.type = type;
        this.ubusCode = ubusCode;
    }

    public Type getType() {
        return type;
    }

    public int getUbusCode() {
        return ubusCode;
    }

    /** ubus 返回码 → 错误类型(可单测) */
    public static ApiError fromUbusCode(int code) {
        switch (code) {
            case 4:
                return new ApiError(Type.CONFIG_NOT_FOUND, "配置项未找到", code);
            case 6:
                return new ApiError(Type.PERMISSION_DENIED, "权限被拒绝", code);
            default:
                return new ApiError(Type.UBUS_ERROR, "ubus 错误 (code=" + code + ")", code);
        }
    }

    @Override
    public String toString() {
        return "ApiError{" + type + ", code=" + ubusCode + ", " + getMessage() + "}";
    }
}