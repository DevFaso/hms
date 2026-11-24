package com.example.hms.controller.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** app.auth.refresh-not-before: every refresh token issued before it mints nothing. */
class AuthControllerPropertiesRefreshCutoffTest {

    private static AuthControllerProperties with(String cutoff) {
        return new AuthControllerProperties("https://localhost", List.of(), false, "", cutoff);
    }

    @Test
    @DisplayName("unset: no refresh token is cut off")
    void unsetCutsNothing() {
        assertThat(with("").refreshNotBefore()).isNull();
        assertThat(with(null).refreshIssuedBeforeCutoff(new Date(0))).isFalse();
    }

    @Test
    @DisplayName("a token issued before the instant is cut off; one issued in that second or later is not")
    void cutsTokensIssuedBefore() {
        AuthControllerProperties props = with(" 2026-11-24T18:00:00.750Z ");
        Instant cutoff = Instant.parse("2026-11-24T18:00:00Z");
        assertThat(props.refreshIssuedBeforeCutoff(Date.from(cutoff.minusSeconds(1)))).isTrue();
        assertThat(props.refreshIssuedBeforeCutoff(Date.from(cutoff))).isFalse();
        assertThat(props.refreshIssuedBeforeCutoff(Date.from(cutoff.plusSeconds(60)))).isFalse();
        assertThat(props.refreshIssuedBeforeCutoff(null)).isFalse();
    }

    @Test
    @DisplayName("a value that is not an ISO-8601 instant stops the start-up")
    void aBadValueStopsTheStartUp() {
        assertThatThrownBy(() -> with("24/11/2026 18:00"))
            .isInstanceOf(BeanInitializationException.class)
            .hasMessageContaining("app.auth.refresh-not-before");
    }
}
