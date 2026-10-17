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
 * QA D33 — {@code GET /me/hospital} answers from the caller's own acting
 * scope, so its matcher admits every authenticated caller. A role list here
 * kept falling behind the roles that exist: the pharmacist was 403'd and the
 * pharmacy registry page went to /error/403.
 */
class SecurityConfigMeHospitalMatcherTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");
    private static final String MATCHER = ".requestMatchers(HttpMethod.GET, \"/me/hospital\", \"/me/hospitals\")";

    @Test
    @DisplayName("the /me/hospital matcher is authenticated(), not a role list")
    void meHospitalIsOpenToEveryAuthenticatedCaller() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int at = source.indexOf(MATCHER);
        assertThat(at).as("matcher declared").isPositive();
        String rule = source.substring(at + MATCHER.length()).stripLeading();
        assertThat(rule).startsWith(".authenticated()");
    }
}
