package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9 #69 — the matcher layer agrees with the annotations: the vitals and
 * lab-result GET matchers admit the pharmacist (the filter chain is
 * first-match-wins and terminal, so a role missing here is 403'd before the
 * annotation that permits it ever runs), while the vitals write matcher
 * stays with the bedside roles.
 */
class SecurityConfigPharmacistReadMatcherTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");

    private static String rolesOf(String source, String matcherLine) {
        int at = source.indexOf(matcherLine);
        assertThat(at).as("matcher declared: %s", matcherLine).isPositive();
        int roles = source.indexOf(".hasAnyAuthority(", at);
        return source.substring(at, source.indexOf(")", roles));
    }

    @Test
    @DisplayName("vitals GET and lab-result GET matchers admit the pharmacist")
    void pharmacistReadMatchers() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(rolesOf(source, ".requestMatchers(HttpMethod.GET, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)"))
            .contains("ROLE_PHARMACIST");
        assertThat(rolesOf(source, ".requestMatchers(HttpMethod.GET, API_LAB_RESULTS, API_LAB_RESULTS_PATTERN)"))
            .contains("ROLE_PHARMACIST");
    }

    @Test
    @DisplayName("the vitals write matcher stays with the bedside roles")
    void vitalsWriteMatcherUnchanged() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(rolesOf(source, ".requestMatchers(HttpMethod.POST, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)"))
            .doesNotContain("ROLE_PHARMACIST");
    }
}
