package com.knowbase.auth;

import java.io.IOException;
import java.util.Set;

import com.knowbase.common.ApiResponse;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 认证过滤器（无 Spring Security，手写 OncePerRequestFilter）。
 *
 * <p>为什么不用 starter-security：那会接管整条 FilterChain 并默认保护所有路径，
 * 与当前 {@code /health} 的开放语义、错误体格式都要重新对齐。这里只需要一件事——
 * 「解析 Bearer、校验、把用户放进线程上下文」。
 *
 * <p>401 在这里直接写 JSON，**不抛异常给 DispatcherServlet**：
 * 过滤器抛出的异常会绕过 {@code @RestControllerAdvice}，落到容器默认错误页，
 * 前端拿到的就不是统一响应体了。因此必须自己序列化 {@link ApiResponse#error}，
 * 并自己回写 {@code X-Trace-Id}（{@code TraceIdFilter} 已写入，这里再写一次是为了
 * 万一上游改动了响应头顺序时仍能保证客户端拿到 traceId）。
 *
 * <p>顺序排在 {@link com.knowbase.common.TraceIdFilter} 之后，保证 401 响应里也有 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /** 不需要 token 的路径。故意用精确匹配而不是前缀匹配：前缀匹配容易把 {@code /auth/login-x} 也放过。 */
    private static final Set<String> WHITELIST = Set.of("/auth/login", "/health", "/error");

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String MISSING_TOKEN_MESSAGE = "未登录或登录已失效";

    /**
     * 独立的小 mapper，不依赖 Web 层的 ObjectMapper bean：
     * 这里只序列化 {@code ApiResponse}（纯字符串/数字），行为必须绝对可控。
     */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final JwtService jwtService;
    private final AuthService authService;

    public AuthFilter(JwtService jwtService, AuthService authService) {
        this.jwtService = jwtService;
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return WHITELIST.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        AuthUser user;
        try {
            String token = extractBearerToken(request);
            user = authService.authenticate(jwtService.parse(token));
        } catch (BusinessException ex) {
            // 只把「可展示的文案」给客户端；不区分缺 token / 签名错 / 用户被禁用。
            writeUnauthorized(response, ex.getMessage());
            return;
        }

        CurrentUser.set(user);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池会复用线程：漏掉 clear 就是把上一个用户的身份借给下一个请求。
            CurrentUser.clear();
        }
    }

    /**
     * 取出 {@code Authorization: Bearer <token>}。
     *
     * <p>scheme 按 RFC 7235 忽略大小写；缺头或格式不对都按「未认证」处理。
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, MISSING_TOKEN_MESSAGE);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, MISSING_TOKEN_MESSAGE);
        }
        return token;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(TraceContext.HEADER, TraceContext.current());

        ApiResponse<Void> body = ApiResponse.error(ErrorCode.UNAUTHORIZED, message);
        try {
            response.getWriter().write(JSON_MAPPER.writeValueAsString(body));
        } catch (RuntimeException ex) {
            // 序列化失败也必须保持 401，不能退化成 500 让前端误判为服务故障。
            log.error("写入 401 响应体失败", ex);
            response.getWriter().write("{\"code\":40101,\"message\":\"" + message + "\",\"data\":null}");
        }
    }
}
