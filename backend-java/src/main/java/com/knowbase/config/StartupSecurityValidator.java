package com.knowbase.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动期配置安全校验。
 *
 * <p>原则：宁可启动失败，也不要带着弱口令、示例占位值或缺失的加密密钥把服务跑起来。
 * 失败时抛出的消息一次性列全部问题，并给出下一步该改哪个变量，避免「改一个、重启一次」的循环。
 *
 * <p>任何情况下都不把密钥原文写进日志，哪怕是前几位——日志会进 CI、进工单、进截图。
 */
@Component
public class StartupSecurityValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityValidator.class);

    /** 命中任一关键字即视为「仍是示例值」，统一小写比较。 */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "please-change", "your-password", "123456", "password", "secret", "admin");

    /** APP_MASTER_KEY 解码后必须正好 32 字节（AES-256）。 */
    private static final int MASTER_KEY_BYTES = 32;

    /** 生产环境内部令牌的最小长度。 */
    private static final int MIN_INTERNAL_TOKEN_LENGTH = 32;

    /**
     * 未被解析的占位符。
     *
     * <p>Spring 的 Binder 遇到解析不了的 {@code ${X}} 并不会报错，而是把字面量原样绑进来，
     * 于是「环境变量没设」会伪装成「口令就是 ${MYSQL_PASSWORD}」。
     * 这里必须把这种值当作「没设置」，否则用户看到的是「仍是示例占位值」这种误导性提示。
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]+}$");

    private static final String MASTER_KEY_FORMAT_PROBLEM =
            "APP_MASTER_KEY 必须是 32 字节的 base64 字符串（生成：openssl rand -base64 32）";

    private final DataSourceProperties dataSourceProperties;
    private final SecurityProperties securityProperties;
    private final Environment environment;

    public StartupSecurityValidator(DataSourceProperties dataSourceProperties,
                                    SecurityProperties securityProperties,
                                    Environment environment) {
        this.dataSourceProperties = dataSourceProperties;
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        String dbPassword = dataSourceProperties.getPassword();
        String masterKey = securityProperties.masterKey();
        String internalToken = securityProperties.internalToken();

        if (!hasText(dbPassword)) {
            problems.add("MYSQL_PASSWORD 未设置（spring.datasource.password）");
        } else {
            checkPlaceholder(problems, "MYSQL_PASSWORD", dbPassword);
        }

        if (!hasText(masterKey)) {
            problems.add("APP_MASTER_KEY 未设置");
        } else {
            checkPlaceholder(problems, "APP_MASTER_KEY", masterKey);
            checkMasterKeyFormat(problems, masterKey);
        }

        if (hasText(internalToken)) {
            checkPlaceholder(problems, "INTERNAL_TOKEN", internalToken);
        }

        boolean devProfile = environment.matchesProfiles("dev");
        boolean prodProfile = environment.matchesProfiles("prod");

        if (prodProfile) {
            if (!hasText(internalToken) || internalToken.trim().length() < MIN_INTERNAL_TOKEN_LENGTH) {
                problems.add("INTERNAL_TOKEN 在生产环境必须至少 32 字符");
            }
            if (!hasText(environment.getProperty("spring.data.redis.password"))) {
                problems.add("REDIS_PASSWORD 在生产环境不能为空");
            }
            // 连接串里的 host/port/db/user 在 prod 没有默认值：漏设时 Binder 会把 ${...} 当字面量拼进 URL，
            // 最终报的是驱动层的连接错误，看不出究竟少了哪个变量。
            if (containsUnresolved(dataSourceProperties.getUrl())
                    || containsUnresolved(dataSourceProperties.getUsername())) {
                problems.add("生产环境数据库连接参数未完整设置（检查 MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USER）");
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("配置校验失败，拒绝启动：");
            for (String problem : problems) {
                message.append(System.lineSeparator()).append("  - ").append(problem);
            }
            message.append(System.lineSeparator()).append("请参考 .env.example 与 README 的「配置与安全」章节。");
            throw new IllegalStateException(message.toString());
        }

        if (devProfile) {
            log.warn("当前使用 dev 配置：数据库连接未启用 TLS，仅限本机开发；部署请设置 SPRING_PROFILES_ACTIVE=prod");
        }
        log.info("启动配置校验通过，active profiles = {}", activeProfileNames());
    }

    /** 值里含示例关键字就记一条问题；只报告变量名，不回显取值。 */
    private void checkPlaceholder(List<String> problems, String envName, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (normalized.contains(marker)) {
                problems.add(envName + " 仍是示例占位值，必须替换");
                return;
            }
        }
    }

    /** masterKey 可不只是「非空」：解不出 base64 或长度不对，加密在运行期才会炸，必须前置拦下。 */
    private void checkMasterKeyFormat(List<String> problems, String masterKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(masterKey.trim());
            if (decoded.length != MASTER_KEY_BYTES) {
                problems.add(MASTER_KEY_FORMAT_PROBLEM);
            }
        } catch (IllegalArgumentException ex) {
            problems.add(MASTER_KEY_FORMAT_PROBLEM);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank() && !UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

    /**
     * 实际生效的 profile 名。
     *
     * <p>只读 {@code getActiveProfiles()} 会漏掉 spring.profiles.default 兜底出来的 dev，
     * 日志里就成了「active profiles = 」空一串，看日志的人会以为 profile 没生效。
     */
    private String activeProfileNames() {
        String[] active = environment.getActiveProfiles();
        return String.join(",", active.length > 0 ? active : environment.getDefaultProfiles());
    }

    /** 值里是否还残留未被解析的 {@code ${...}}。 */
    private boolean containsUnresolved(String value) {
        return value != null && value.contains("${");
    }
}
