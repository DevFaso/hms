package com.example.hms.payload.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationRequestDTOTest {

    // ─── No-arg constructor ──────────────────────────────────────

    @Test
    void noArgConstructor() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        assertThat(dto.getEmail()).isNull();
        assertThat(dto.getToken()).isNull();
    }

    // ─── Getters / Setters ───────────────────────────────────────

    @Test
    void setAndGetEmail() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setEmail("test@example.com");
        assertThat(dto.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void setAndGetToken() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setToken("abc-123-token");
        assertThat(dto.getToken()).isEqualTo("abc-123-token");
    }

    @Test
    void setEmailToNull() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setEmail("initial@test.com");
        dto.setEmail(null);
        assertThat(dto.getEmail()).isNull();
    }

    @Test
    void setTokenToNull() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setToken("initial");
        dto.setToken(null);
        assertThat(dto.getToken()).isNull();
    }

    @Test
    void setAllFields() {
        EmailVerificationRequestDTO dto = new EmailVerificationRequestDTO();
        dto.setEmail("user@domain.com");
        dto.setToken("verification-token-xyz");
        assertThat(dto.getEmail()).isEqualTo("user@domain.com");
        assertThat(dto.getToken()).isEqualTo("verification-token-xyz");
    }
}
