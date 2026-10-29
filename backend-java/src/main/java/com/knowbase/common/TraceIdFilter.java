package com.knowbase.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立 traceId 并写入 MDC / 响应头。
 *
 * <p>规则：
 * <ul>
 *   <li>上游（Nginx、前端、Python）带了 {@code X-Trace-Id} 且格式合法 → 沿用，保证跨服务串联；</li>
 *   <li>否则生成 32 位十六进制随机串；</li>
 *   <li>无论成功失败，响应头都回写 traceId，用户报障时可直接提供。</li>
 * </ul>
 * 只接受 8–64 位、仅含 {@code [A-Za-z0-9-_]} 的值，避免把攻击载荷写进日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request.getHeader(TraceContext.HEADER));
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private String resolveTraceId(String incoming) {
        if (incoming != null && !incoming.isBlank() && incoming.length() <= MAX_LENGTH
                && incoming.matches("[A-Za-z0-9_-]+")) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
