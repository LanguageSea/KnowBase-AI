package com.knowbase.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查入口。
 *
 * <p>语义：
 * <ul>
 *   <li>200 + status=UP —— 全部依赖可用，可以接流量；</li>
 *   <li>503 + status=DEGRADED —— 至少一个依赖不可用，body 里指出是哪一个。</li>
 * </ul>
 * 不使用统一响应体外壳：健康检查的消费方是脚本与容器探针，扁平结构更好解析。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public ResponseEntity<HealthReport> health() {
        HealthReport report = healthService.check();
        HttpStatus status = "UP".equals(report.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(report);
    }
}
