package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoleNames.bareRole")
class RoleNamesTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "ROLE_DOCTOR, DOCTOR",
        "DOCTOR, DOCTOR",
        "'  ROLE_NURSE  ', NURSE",
        "ROLE_HOSPITAL_ADMIN, HOSPITAL_ADMIN",
        // A display name with no prefix passes through untouched.
        "Doctor, Doctor",
    })
    @DisplayName("strips the ROLE_ prefix and nothing else")
    void stripsThePrefix(String raw, String expected) {
        assertThat(RoleNames.bareRole(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "Unknown Role", "  Unknown Role ", "ROLE_"})
    @DisplayName("null for nothing to show, including the Unknown Role sentence legacy rows carry")
    void nullForNothingToShow(String raw) {
        assertThat(RoleNames.bareRole(raw)).isNull();
    }

    @Test
    @DisplayName("the sentinel is the literal the audit writer stamps")
    void sentinelMatchesTheWriter() {
        // core/role-token.ts in the portal matches on this exact string; a
        // rewording here must be made there too.
        assertThat(RoleNames.UNKNOWN_ROLE).isEqualTo("Unknown Role");
    }
}
