package com.knowbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关配置。
 *
 * <p>这里不写 {@code @Validated} / {@code @NotBlank}：jakarta validation 抛出的是一行英文通用消息，
 * 运维看到后还得回头翻文档。改由 {@link StartupSecurityValidator} 统一校验并给出中文可操作提示。
 *
 * @param masterKey     {@code APP_MASTER_KEY}，32 字节随机密钥的 base64 编码，
 *                      用于加密 {@code ai_model_config.api_key_cipher}。
 *                      该密钥一旦丢失，已加密的模型 API Key 将永久无法解密恢复，
 *                      只能全部重新录入；必须单独备份，绝不可提交进仓库。
 * @param internalToken {@code INTERNAL_TOKEN}，Java 服务调用 Python AI 服务的内部令牌。
 * @param allowPrivateModelUrls 是否允许模型地址指向私网。默认关闭；仅本地 Ollama 开发时可显式开启。
 */
@ConfigurationProperties(prefix = "knowbase.security")
public record SecurityProperties(String masterKey, String internalToken,
        Boolean allowPrivateModelUrls) {

    public boolean privateModelUrlsAllowed() {
        return Boolean.TRUE.equals(allowPrivateModelUrls);
    }
}
