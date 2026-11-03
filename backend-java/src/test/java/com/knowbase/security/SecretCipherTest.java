package com.knowbase.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import com.knowbase.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomIvAndRoundTrips() {
        SecretCipher cipher = cipher(KEY);

        String first = cipher.encrypt("sk-live-1234567890");
        String second = cipher.encrypt("sk-live-1234567890");

        assertThat(first).startsWith("v1:");
        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("sk-live-1234567890");
        assertThat(cipher.decrypt(first)).isEqualTo("sk-live-1234567890");
    }

    @Test
    void wrongMasterKeyCannotDecrypt() {
        String ciphertext = cipher(KEY).encrypt("secret-value");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        SecretCipher other = cipher(Base64.getEncoder().encodeToString(otherKey));

        assertThatThrownBy(() -> other.decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解密");
    }

    @Test
    void maskKeepsPrefixAndLastFourOnly() {
        assertThat(SecretCipher.mask("sk-live-1234567890")).isEqualTo("sk-****7890");
        assertThat(SecretCipher.mask("abc")).isEqualTo("****bc");
        assertThat(SecretCipher.mask("")).isNull();
    }

    private static SecretCipher cipher(String key) {
        return new SecretCipher(new SecurityProperties(key, "internal-token", false));
    }
}
