package com.knowbase.common;

import org.slf4j.MDC;

/**
 * traceId 上下文。
 *
 * <p>请求进入时由 {@link TraceIdFilter} 写入 MDC，
 * 所有响应体与日志都从这里取，保证跨服务排查时能串起来。
 */
public final class TraceContext {

    /** 响应头名称，Java → Python 透传时沿用同一个头。 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 中的键名，与 application.yml 的日志 pattern 一致。 */
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /** 当前请求的 traceId；不在请求线程中时返回空串而不是 null。 */
    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
