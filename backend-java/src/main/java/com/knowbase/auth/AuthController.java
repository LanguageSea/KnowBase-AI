package com.knowbase.auth;

import com.knowbase.common.ApiResponse;
import com.knowbase.audit.Audit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（docs/API_SPEC.md §1）。
 *
 * <p>两个接口都不自己拼响应体、不自己处理异常：
 * 成功走 {@link ApiResponse#ok}，失败抛 {@code BusinessException} 交给全局处理器，
 * 保证 code / message / traceId / timestamp 与其它模块完全一致。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /auth/login —— 唯一不需要 token 的业务接口。 */
    @PostMapping("/login")
    @Audit(action = "LOGIN", resourceType = "USER")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ApiResponse.ok(response);
    }

    /**
     * GET /auth/me —— 前端刷新页面后恢复会话。
     *
     * <p>能到这里说明 {@link AuthFilter} 已经完成签名、用户状态、token_version 三重校验。
     */
    @GetMapping("/me")
    public ApiResponse<AuthUser> me() {
        return ApiResponse.ok(authService.currentUser());
    }

    /** POST /auth/logout：token_version 自增后，当前 token 与其它会话同时失效。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok(null);
    }

    /** PUT /auth/password：校验当前密码，改密成功后要求所有会话重新登录。 */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok(null);
    }

    /**
     * 客户端 IP。
     *
     * <p>刻意不自己解析 {@code X-Forwarded-For}：那串头可被客户端伪造。
     * 生产环境由 {@code server.forward-headers-strategy: native} 让容器按可信代理链还原。
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
