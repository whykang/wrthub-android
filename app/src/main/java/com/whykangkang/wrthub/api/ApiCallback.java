package com.whykangkang.wrthub.api;

/**
 * 结果回调,对应 iOS 的 (Result&lt;T, APIError&gt;) -> Void。
 * 由 OpenWrtApi 保证在主线程回调。
 */
public interface ApiCallback<T> {
    void onSuccess(T result);

    void onFailure(ApiError error);
}