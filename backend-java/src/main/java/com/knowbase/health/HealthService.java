package com.knowbase.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
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
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final HttpClient httpClient;
    private final String qdrantUrl;
    private final int timeoutMs;

    public HealthService(DataSource dataSource,
                         StringRedisTemplate redisTemplate,
                         @Value("${knowbase.qdrant.url}") String qdrantUrl,
                         @Value("${knowbase.qdrant.timeout-ms:2000}") int timeoutMs) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.qdrantUrl = qdrantUrl.endsWith("/") ? qdrantUrl.substring(0, qdrantUrl.length() - 1) : qdrantUrl;
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
            return ComponentHealth.down("mysql", elapsedMs(start), rootMessage(ex));
        }
    }

    private ComponentHealth checkRedis() {
        long start = System.nanoTime();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                return ComponentHealth.up("redis", elapsedMs(start));
            }
            return ComponentHealth.down("redis", elapsedMs(start), "unexpected ping reply: " + pong);
        } catch (Exception ex) {
            log.warn("redis health check failed: {}", rootMessage(ex));
            return ComponentHealth.down("redis", elapsedMs(start), rootMessage(ex));
        }
    }

    private ComponentHealth checkQdrant() {
        long start = System.nanoTime();
        List<String> errors = new ArrayList<>();
        // Qdrant 在不同版本上暴露 healthz / readyz，逐个尝试，都失败才算 DOWN。
        // 两处错误都保留：只报最后一个会让人误以为前一个探针没问题。
        for (String path : List.of("/healthz", "/readyz")) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(qdrantUrl + path))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return ComponentHealth.up("qdrant", elapsedMs(start));
                }
                errors.add(path + " -> HTTP " + response.statusCode());
            } catch (Exception ex) {
                errors.add(path + " -> " + rootMessage(ex));
            }
        }
        String detail = String.join("; ", errors);
        log.warn("qdrant health check failed: {}", detail);
        return ComponentHealth.down("qdrant", elapsedMs(start), detail);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
