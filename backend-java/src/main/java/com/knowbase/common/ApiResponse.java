package com.knowbase.common;

import java.time.Instant;

/**
 * 统一响应体。
 *
 * <p>成功用 code=0；失败用 {@link ErrorCode} 中的业务码。
 * HTTP 状态码与业务码同时使用：HTTP 表达传输层结果，业务码表达具体原因，
 * 前端只需按业务码分支，不必在各处写 status 判断。
 *
 * @param code      业务码，0 表示成功
 * @param message   可直接展示给用户的文案
 * @param data      业务数据，失败时为 null
 * @param traceId   当前请求的 traceId，便于用户报障时定位日志
 * @param timestamp 服务端时间（ISO-8601）
 */
public record ApiResponse<T>(int code, String message, T data, String traceId, String timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data, TraceContext.current(), Instant.now().toString());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, TraceContext.current(), Instant.now().toString());
    }
}
