package com.knowbase.health;

import java.util.List;

/**
 * 聚合健康报告。
 *
 * @param status     UP = 全部依赖正常；DEGRADED = 至少一个依赖不可用
 * @param timestamp  探测时间（ISO-8601）
 * @param components 各依赖的明细，顺序固定为 mysql / redis / qdrant
 */
public record HealthReport(String status, String timestamp, List<ComponentHealth> components) {
}
