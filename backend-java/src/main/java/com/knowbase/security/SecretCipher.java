package com.knowbase.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.knowbase.config.SecurityProperties;
import org.springframework.stereotype.Component;

/**
 * API key encryption using AES-256-GCM. The stored value is
 * {@code v1:<iv-base64>:<cipher+tag-base64>}; a fresh IV is generated for every write.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(SecurityProperties properties) {
        String masterKey = properties.masterKey();
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("APP_MASTER_KEY 未设置");
        }
        byte[] raw = Base64.getDecoder().decode(masterKey.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("APP_MASTER_KEY 必须是 32 字节的 base64 字符串");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("模型密钥加密失败", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("不支持的密钥密文格式");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("模型密钥无法解密，请确认 APP_MASTER_KEY 未丢失或被替换", ex);
        }
    }

    /** Never expose the complete key, even to administrators. */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (apiKey.length() <= 8) {
            return "****" + apiKey.substring(apiKey.length() - Math.min(2, apiKey.length()));
        }
        int prefixLength = Math.min(3, apiKey.length() - 5);
        return apiKey.substring(0, prefixLength) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
