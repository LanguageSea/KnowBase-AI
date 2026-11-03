package com.knowbase.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowbase.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard(
            new SecurityProperties("unused", "unused", false));

    @Test
    void rejectsLoopbackPrivateAndMetadataAddresses() {
        assertRejected("https://127.0.0.1/v1");
        assertRejected("https://10.1.2.3/v1");
        assertRejected("https://172.16.8.9/v1");
        assertRejected("https://192.168.1.10/v1");
        assertRejected("https://169.254.169.254/latest/meta-data");
        assertRejected("https://[::1]/v1");
        assertRejected("https://[fd00::1]/v1");
    }

    @Test
    void rejectsHttpAndCredentialBearingUrlsByDefault() {
        assertRejected("http://8.8.8.8/v1");
        assertRejected("https://user:pass@8.8.8.8/v1");
    }

    @Test
    void acceptsPublicHttpsAddress() {
        assertThat(guard.validate("https://8.8.8.8/v1/").toString())
                .isEqualTo("https://8.8.8.8/v1");
    }

    @Test
    void explicitDevelopmentSwitchAllowsLocalOllamaOnlyWhenEnabled() {
        SsrfGuard developmentGuard = new SsrfGuard(
                new SecurityProperties("unused", "unused", true));

        assertThat(developmentGuard.validate("http://127.0.0.1:11434/v1").toString())
                .isEqualTo("http://127.0.0.1:11434/v1");
    }

    private void assertRejected(String url) {
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(ModelEndpointException.class);
    }
}
