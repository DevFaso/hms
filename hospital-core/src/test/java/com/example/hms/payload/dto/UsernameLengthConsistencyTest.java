package com.example.hms.payload.dto;

import com.example.hms.model.User;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D37: the server generates usernames like "firstname.lastname0271" (up to 39
 * characters); the update DTO must accept every name the generator or account
 * setup can produce, and both must share the column limit.
 */
class UsernameLengthConsistencyTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void updateAcceptsAGeneratedUsername() {
        UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
        dto.setUsername("qareception.hopitala0271");
        assertThat(validator.validateProperty(dto, "username")).isEmpty();
    }

    @Test
    void updateAcceptsTheLongestGeneratedShape() {
        // base capped at 30 + "." + 8-char fallback suffix = 39.
        UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
        dto.setUsername("a".repeat(30) + "." + "b".repeat(8));
        assertThat(validator.validateProperty(dto, "username")).isEmpty();
    }

    @Test
    void updateRejectsAboveTheColumnLimit() {
        UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
        dto.setUsername("a".repeat(User.USERNAME_MAX_LENGTH + 1));
        assertThat(validator.validateProperty(dto, "username")).isNotEmpty();
    }
}
