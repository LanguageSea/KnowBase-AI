package com.knowbase.health;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 单个依赖组件的健康结果。
 *
 * @param name      组件名，如 mysql / redis / qdrant
 * @param status    UP 或 DOWN
 * @param latencyMs 本次探测耗时（毫秒），超时也计入
 * @param detail    DOWN 时的原因摘要；UP 时为 null
 */
public record ComponentHealth(String name, String status, long latencyMs, String detail) {

    public static ComponentHealth up(String name, long latencyMs) {
        return new ComponentHealth(name, "UP", latencyMs, null);
    }

    public static ComponentHealth down(String name, long latencyMs, String detail) {
        return new ComponentHealth(name, "DOWN", latencyMs, detail);
    }

    /** 仅供 Java 内部聚合判断，不进入响应体（否则 JSON 里会多出一个冗余的 up 字段）。 */
    @JsonIgnore
    public boolean isUp() {
        return "UP".equals(status);
    }
}
