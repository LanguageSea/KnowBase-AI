package com.knowbase.health;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 真实依赖探活。
 *
 * <p>刻意不复用 Actuator：本项目的验收标准是「停掉 Qdrant 后能指出是哪个依赖挂了」，
 * 而 Actuator 的默认格式会把细节藏进 details 字段，脚本解析起来别扭。
 *
 * <p>三条探测都必须有超时上限，任何一个依赖卡死都不能把 /health 拖住。
 *
 * <p>返回给客户端的 detail 只放稳定短码（{@code TIMEOUT}、{@code AUTH_FAILED} 等），
 * 异常类名、原文、SQL 语句一律只进服务端日志：这类信息会泄露内网拓扑、账号是否存在、
 * 甚至驱动版本，而排障方本来就该拿 traceId 去查日志。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final HttpClient httpClient;
    private final String qdrantUrl;
    private final String qdrantApiKey;
    private final int timeoutMs;

    public HealthService(DataSource dataSource,
                         StringRedisTemplate redisTemplate,
                         @Value("${knowbase.qdrant.url}") String qdrantUrl,
                         @Value("${knowbase.qdrant.api-key:}") String qdrantApiKey,
                         @Value("${knowbase.qdrant.timeout-ms:2000}") int timeoutMs) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.qdrantUrl = qdrantUrl.endsWith("/") ? qdrantUrl.substring(0, qdrantUrl.length() - 1) : qdrantUrl;
        this.qdrantApiKey = qdrantApiKey;
        this.timeoutMs = timeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public HealthReport check() {
        List<ComponentHealth> components = List.of(checkMysql(), checkRedis(), checkQdrant());
        boolean allUp = components.stream().allMatch(ComponentHealth::isUp);
        return new HealthReport(allUp ? "UP" : "DEGRADED", Instant.now().toString(), components);
    }

    private ComponentHealth checkMysql() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            rs.next();
            return ComponentHealth.up("mysql", elapsedMs(start));
        } catch (Exception ex) {
            log.warn("mysql health check failed: {}", rootMessage(ex));
            return ComponentHealth.down("mysql", elapsedMs(start), classify(ex));
        }
    }

    private ComponentHealth checkRedis() {
        long start = System.nanoTime();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                return ComponentHealth.up("redis", elapsedMs(start));
            }
            log.warn("redis health check returned unexpected reply: {}", pong);
            return ComponentHealth.down("redis", elapsedMs(start), "REDIS_ERROR");
        } catch (Exception ex) {
            log.warn("redis health check failed: {}", rootMessage(ex));
            return ComponentHealth.down("redis", elapsedMs(start), "REDIS_ERROR");
        }
    }

    private ComponentHealth checkQdrant() {
        long start = System.nanoTime();
        // codes 给客户端，reasons 只进日志：两处错误都保留，
        // 只报最后一个会让人误以为前一个探针没问题。
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        // 未配置密钥时探 /healthz、/readyz（Qdrant 的免鉴权存活探针）；
        // 配了密钥就改探 /collections——上面两个探针在 Qdrant 上根本不校验 api-key，
        // 用它们即使密钥配错也照样 UP，等于把「服务活着」当成「我们能用它」。
        List<String> paths = hasApiKey() ? List.of("/collections") : List.of("/healthz", "/readyz");
        for (String path : paths) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(qdrantUrl + path))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET();
                // 开了鉴权的 Qdrant 对未带 api-key 的请求回 401，
                // 不带这个头会把「密钥没配」误报成「服务不可用」。
                if (hasApiKey()) {
                    builder.header("api-key", qdrantApiKey);
                }
                HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return ComponentHealth.up("qdrant", elapsedMs(start));
                }
                boolean authFailure = response.statusCode() == 401 || response.statusCode() == 403;
                codes.add(path + "=" + (authFailure ? "AUTH_FAILED" : "HTTP_" + response.statusCode()));
                reasons.add(path + " -> HTTP " + response.statusCode());
            } catch (Exception ex) {
                codes.add(path + "=" + classify(ex));
                reasons.add(path + " -> " + rootMessage(ex));
            }
        }
        log.warn("qdrant health check failed: {}", String.join("; ", reasons));
        return ComponentHealth.down("qdrant", elapsedMs(start), String.join("; ", codes));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Qdrant 是否配置了 api-key。 */
    private boolean hasApiKey() {
        return qdrantApiKey != null && !qdrantApiKey.isBlank();
    }

    /**
     * 把异常归类成稳定的短码，供客户端展示。
     *
     * <p>沿 cause 链逐层判断：Spring 的 DataAccessException 会把真正的 SQLException 包一层，
     * 只看最外层永远只能得到 ERROR。超时类要先于通用 IOException 判断，否则会被吞成 IO_ERROR。
     */
    private String classify(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                return sqlState != null && sqlState.startsWith("28") ? "AUTH_FAILED" : "DB_ERROR";
            }
            if (current instanceof HttpConnectTimeoutException || current instanceof HttpTimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof ConnectException || current instanceof ClosedChannelException) {
                return "CONNECTION_REFUSED";
            }
            if (current instanceof IOException) {
                return "IO_ERROR";
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return "ERROR";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName()
                : current.getClass().getSimpleName() + ": " + message;
    }
}
