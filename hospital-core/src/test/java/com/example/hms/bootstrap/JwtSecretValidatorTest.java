package com.example.hms.bootstrap;

import com.example.hms.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtSecretValidatorTest {

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private ApplicationArguments args;

    private JwtSecretValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JwtSecretValidator(jwtProperties);
    }

    // ── run() with valid secret ──────────────────────────────────

    @Test
    void run_withValidSecret_completesSuccessfully() {
        String validSecret = "a".repeat(32);
        when(jwtProperties.getSecret()).thenReturn(validSecret);

        assertThatCode(() -> validator.run(args)).doesNotThrowAnyException();
    }

    @Test
    void run_withLongSecret_completesSuccessfully() {
        String longSecret = "abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmnopqrstuvwxyz";
        when(jwtProperties.getSecret()).thenReturn(longSecret);

        assertThatCode(() -> validator.run(args)).doesNotThrowAnyException();
    }

    @Test
    void run_withExactly32CharSecret_completesSuccessfully() {
        String exact32 = "12345678901234567890123456789012";
        when(jwtProperties.getSecret()).thenReturn(exact32);

        assertThatCode(() -> validator.run(args)).doesNotThrowAnyException();
    }

    // ── run() with null secret ───────────────────────────────────

    @Test
    void run_withNullSecret_throwsIllegalStateException() {
        when(jwtProperties.getSecret()).thenReturn(null);

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is missing");
    }

    // ── run() with blank secrets ─────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "  \t\n  "})
    void run_withBlankOrNullSecret_throwsIllegalStateException(String secret) {
        when(jwtProperties.getSecret()).thenReturn(secret);

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void run_withEmptySecret_throwsMissingMessage() {
        when(jwtProperties.getSecret()).thenReturn("");

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is missing");
    }

    @Test
    void run_withWhitespaceOnlySecret_throwsMissingMessage() {
        when(jwtProperties.getSecret()).thenReturn("     ");

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is missing");
    }

    // ── run() with short secret ──────────────────────────────────

    @Test
    void run_withShortSecret_throwsIllegalStateException() {
        when(jwtProperties.getSecret()).thenReturn("short");

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret too short");
    }

    @Test
    void run_with31CharSecret_throwsTooShortMessage() {
        String thirtyOne = "a".repeat(31);
        when(jwtProperties.getSecret()).thenReturn(thirtyOne);

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret too short (min 32 chars)");
    }

    @Test
    void run_with1CharSecret_throwsTooShortMessage() {
        when(jwtProperties.getSecret()).thenReturn("x");

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret too short");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 15, 20, 25, 30, 31})
    void run_withSecretShorterThan32_throwsTooShort(int length) {
        String secret = "a".repeat(length);
        when(jwtProperties.getSecret()).thenReturn(secret);

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret too short");
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 33, 64, 128, 256})
    void run_withSecretAtLeast32_doesNotThrow(int length) {
        String secret = "a".repeat(length);
        when(jwtProperties.getSecret()).thenReturn(secret);

        assertThatCode(() -> validator.run(args)).doesNotThrowAnyException();
    }

    // ── boundary: exactly at threshold ───────────────────────────

    @Test
    void run_secretBoundaryAt32_passesValidation() {
        when(jwtProperties.getSecret()).thenReturn("X".repeat(32));

        assertThatCode(() -> validator.run(args)).doesNotThrowAnyException();
    }

    @Test
    void run_secretBoundaryAt31_failsValidation() {
        when(jwtProperties.getSecret()).thenReturn("X".repeat(31));

        assertThatThrownBy(() -> validator.run(args))
                .isInstanceOf(IllegalStateException.class);
    }
}
