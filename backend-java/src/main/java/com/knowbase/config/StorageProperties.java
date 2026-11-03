package com.knowbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置（docs/DEVELOPMENT_PLAN.md D3）。
 *
 * <p>Stage 1 只用宿主目录挂载，不引入 MinIO。uploadDir 允许相对路径，
 * 解析时以工作目录为基准转绝对路径；目录不存在则在上传时创建。
 */
@ConfigurationProperties(prefix = "knowbase.storage")
public record StorageProperties(String uploadDir) {
}
