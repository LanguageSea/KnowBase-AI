package com.knowbase.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p>编码规则：前 3 位对齐 HTTP 语义，后 2 位区分具体原因。
 * 客户端只允许依赖 {@code code}，不允许依赖 {@code message}（文案会变）。
 */
public enum ErrorCode {

    VALIDATION_ERROR(40001, "参数校验失败", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40101, "未认证或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40301, "无权访问该资源", HttpStatus.FORBIDDEN),
    NOT_FOUND(40401, "资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(40501, "请求方法不允许", HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(40901, "资源状态冲突", HttpStatus.CONFLICT),
    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    DEPENDENCY_UNAVAILABLE(50301, "依赖服务不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
