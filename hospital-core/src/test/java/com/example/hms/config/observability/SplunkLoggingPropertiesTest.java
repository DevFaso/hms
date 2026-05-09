package com.example.hms.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SplunkLoggingProperties}.
 *
 * <p>The class lives under {@code config/**} which is excluded from the JaCoCo coverage gate, so
 * these tests don't directly contribute to the 80% verification. They exist because the
 * fail-fast contract (boot must die when enabled-without-credentials) is the only thing that
 * stops a misconfigured prod release from silently dropping logs at 02:00.
 */
class SplunkLoggingPropertiesTest {

    @Test
    void defaultsAreSafeNoOp_andValidationPasses_whenDisabled() {
        SplunkLoggingProperties props = new SplunkLoggingProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getHec().getUrl()).isEmpty();
        assertThat(props.getHec().getToken()).isEmpty();
        assertThat(props.getHec().getIndex()).isEqualTo("main");
        assertThat(props.getHec().getSource()).isEqualTo("hms-backend");
        assertThat(props.getHec().getSourceType()).isEqualTo("spring-boot:json");

        // No exception when disabled, even with everything blank — local-dev path must boot.
        props.validateWhenEnabled();
    }

    @Test
    void failsFast_whenEnabledWithoutUrl() {
        SplunkLoggingProperties props = new SplunkLoggingProperties();
        props.setEnabled(true);
        props.getHec().setToken("a-token");
        // url left blank

        assertThatThrownBy(props::validateWhenEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("hec.url is blank");
    }

    @Test
    void failsFast_whenEnabledWithoutToken() {
        SplunkLoggingProperties props = new SplunkLoggingProperties();
        props.setEnabled(true);
        props.getHec().setUrl("https://splunk.example.com:8088");
        // token left blank

        assertThatThrownBy(props::validateWhenEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("hec.token is blank");
    }

    @Test
    void failsFast_whenUrlIsNotHttps() {
        SplunkLoggingProperties props = new SplunkLoggingProperties();
        props.setEnabled(true);
        props.getHec().setUrl("http://splunk.example.com:8088"); // plain http
        props.getHec().setToken("a-token");

        assertThatThrownBy(props::validateWhenEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be HTTPS");
    }

    @Test
    void passesValidation_whenEnabledWithHttpsUrlAndToken() {
        SplunkLoggingProperties props = new SplunkLoggingProperties();
        props.setEnabled(true);
        props.getHec().setUrl("https://splunk.example.com:8088");
        props.getHec().setToken("a-token");

        // Just must not throw.
        props.validateWhenEnabled();
    }
}
