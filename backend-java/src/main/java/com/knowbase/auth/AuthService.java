package com.knowbase.auth;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.auth.UserRepository.UserAccount;
import com.knowbase.auth.UserRepository.UserState;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录与令牌校验。
 *
 * <p>两条硬规则：
 * <ul>
 *   <li>「用户名不存在」与「密码错误」对外是同一句话、同一个状态码，不给账号枚举留接口；</li>
 *   <li>口令明文与 BCrypt 哈希不写日志、不进异常消息、不进响应体——日志会被复制进工单和截图。</li>
 * </ul>
 *
 * <p>登录失败计数与锁定由 Redis 承担；JWT 的 token_version 每次从数据库复核。
 * 登录成功会轮换版本，因此同一账号只保留最新登录设备；登出、改密、禁用也会
 * 让已签发 token 立即失效。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 用户名不存在 / 密码错误共用，避免泄露账号是否存在。 */
    private static final String BAD_CREDENTIALS_MESSAGE = "用户名或密码错误";

    private static final String ACCOUNT_DISABLED_MESSAGE = "账号已禁用";

    /** token 过期、被篡改、用户被禁用、token_version 已自增：对外只给这一句。 */
    private static final String SESSION_INVALID_MESSAGE = "登录状态已失效，请重新登录";

    private static final String FAIL_USER_NOT_FOUND = "USER_NOT_FOUND";
    private static final String FAIL_BAD_CREDENTIALS = "BAD_CREDENTIALS";
    private static final String FAIL_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    public AuthService(UserRepository userRepository, JwtService jwtService,
            PasswordEncoder passwordEncoder, LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * 登录。
     *
     * <p>校验顺序是刻意的：先比密码再看状态。否则「账号已禁用」会变成一个
     * 不需要知道密码就能确认账号存在的探测口。
     */
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        String username = request.username().trim();
        LocalDateTime now = LocalDateTime.now();
        loginAttemptService.assertNotLocked(username);

        UserAccount account = userRepository.findByUsername(username).orElse(null);
        if (account == null) {
            recordLoginLog(null, username, ip, userAgent, false, FAIL_USER_NOT_FOUND, now);
            loginAttemptService.recordFailure(username);
            log.info("登录失败：账号不存在 username={}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE);
        }

        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            recordLoginLog(account.id(), account.username(), ip, userAgent, false, FAIL_BAD_CREDENTIALS, now);
            loginAttemptService.recordFailure(username);
            log.info("登录失败：密码不匹配 userId={} username={}", account.id(), account.username());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE);
        }

        if (!STATUS_ACTIVE.equals(account.status())) {
            recordLoginLog(account.id(), account.username(), ip, userAgent, false, FAIL_ACCOUNT_DISABLED, now);
            log.info("登录失败：账号已禁用 userId={} username={}", account.id(), account.username());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, ACCOUNT_DISABLED_MESSAGE);
        }

        // 新登录轮换 token_version，旧设备在下一个请求收到 401。
        int tokenVersion = userRepository.rotateTokenVersionForLogin(account.id(), now);

        // 角色每次从库里取：与 token 解耦，撤权下一个请求就生效。
        List<String> roles = userRepository.findRoleCodes(account.id());
        String token = jwtService.issue(account.id(), account.username(), tokenVersion);
        loginAttemptService.clearFailures(username);
        userRepository.touchLastLogin(account.id(), now);
        recordLoginLog(account.id(), account.username(), ip, userAgent, true, null, now);

        log.info("登录成功 userId={} username={} roles={}", account.id(), account.username(), roles);
        return LoginResponse.of(token, jwtService.ttlSeconds(),
                new AuthUser(account.id(), account.username(), account.nickname(), roles));
    }

    /**
     * 令牌 → 当前用户，每个受保护请求都执行。
     *
     * <p>只验签名是不够的：用户可能已被禁用，或登出/改密让 {@code token_version} 自增。
     * 因此必须回查一次库，这也是 T2.2「禁用后旧 token 立即失效」的实现基础。
     *
     * @throws BusinessException 401/40101，用户不存在、非 ACTIVE、token_version 不匹配
     */
    public AuthUser authenticate(JwtService.ParsedToken token) {
        UserState state = userRepository.findStateById(token.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, SESSION_INVALID_MESSAGE));

        if (!STATUS_ACTIVE.equals(state.status())) {
            log.warn("拒绝访问：账号非 ACTIVE userId={} status={}", state.id(), state.status());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, SESSION_INVALID_MESSAGE);
        }
        if (state.tokenVersion() != token.tokenVersion()) {
            log.warn("拒绝访问：token_version 已失效 userId={} tokenVer={} currentVer={}",
                    state.id(), token.tokenVersion(), state.tokenVersion());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, SESSION_INVALID_MESSAGE);
        }
        return new AuthUser(state.id(), state.username(), state.nickname(),
                userRepository.findRoleCodes(state.id()));
    }

    /** 当前请求的用户（{@code GET /auth/me}），已在 {@link AuthFilter} 中校验并加载。 */
    public AuthUser currentUser() {
        return CurrentUser.require();
    }

    /** 登出只自增 token_version，不维护黑名单；所有已签发 token 一起失效。 */
    public void logout() {
        AuthUser user = CurrentUser.require();
        userRepository.invalidateTokens(user.id(), LocalDateTime.now());
        log.info("用户登出 userId={}", user.id());
    }

    public void changePassword(ChangePasswordRequest request) {
        AuthUser user = CurrentUser.require();
        String currentHash = userRepository.findPasswordHash(user.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, SESSION_INVALID_MESSAGE));
        if (!passwordEncoder.matches(request.currentPassword(), currentHash)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前密码不正确");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新密码不能与当前密码相同");
        }
        userRepository.updatePasswordAndInvalidateTokens(
                user.id(), passwordEncoder.encode(request.newPassword()), LocalDateTime.now());
        log.info("用户修改密码并失效旧 token userId={}", user.id());
    }

    /**
     * 写登录日志，失败不打断主流程。
     *
     * <p>日志表写不进去（磁盘满、锁等待）不该让「密码错误」变成 500：
     * 那会把「账号是否存在」这个信息通过状态码差异泄露出去。
     */
    private void recordLoginLog(Integer userId, String username, String ip, String userAgent,
                                boolean success, String failReason, LocalDateTime at) {
        try {
            userRepository.insertLoginLog(userId, username, ip, userAgent, success, failReason, at);
        } catch (RuntimeException ex) {
            log.warn("写入登录日志失败 userId={} username={} error={}",
                    userId, username, ex.getClass().getSimpleName());
        }
    }
}
