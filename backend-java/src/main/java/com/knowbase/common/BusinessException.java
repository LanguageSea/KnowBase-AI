package com.knowbase.common;

/**
 * 可预期的业务异常。
 *
 * <p>抛出它表示「这是用户能理解、且不需要看堆栈的失败」。
 * 不可预期的异常一律走通用处理器，对外只返回 traceId，堆栈只进日志。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
