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
 * E9 #67 (D5, slice b2) — the lab matchers agree with the annotations: the
 * clinical lab paths (orders, results, specimens, acknowledge, transition,
 * PATCH) no longer admit HOSPITAL_ADMIN at the edge, while the lab
 * configuration and integration matchers (test definitions, QC events,
 * reflex rules, HL7 inbound, instrument outbox) keep it.
 */
class SecurityConfigLabMatcherTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");

    private static String rolesOf(String source, String matcherLine) {
        int at = source.indexOf(matcherLine);
        assertThat(at).as("matcher declared: %s", matcherLine).isPositive();
        int roles = source.indexOf(".hasAnyAuthority(", at);
        return source.substring(at, source.indexOf(")", roles));
    }

    @Test
    @DisplayName("clinical lab matchers refuse HOSPITAL_ADMIN")
    void clinicalLabMatchersWithoutTheRole() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        for (String matcher : new String[] {
            ".requestMatchers(HttpMethod.GET, API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)",
            ".requestMatchers(HttpMethod.POST, API_LAB_ORDERS)",
            ".requestMatchers(HttpMethod.GET, API_LAB_RESULTS, API_LAB_RESULTS_PATTERN)",
            ".requestMatchers(HttpMethod.POST, API_LAB_RESULTS + \"/*/acknowledge\")",
            ".requestMatchers(HttpMethod.PATCH, API_LAB_ORDERS_PATTERN, API_LAB_RESULTS_PATTERN)",
            ".requestMatchers(HttpMethod.GET,  API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)",
            ".requestMatchers(HttpMethod.POST, API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)",
            "API_LAB_ORDERS + \"/*/specimens\")"}) {
            assertThat(rolesOf(source, matcher)).as(matcher).doesNotContain("ROLE_HOSPITAL_ADMIN");
        }
    }

    @Test
    @DisplayName("lab configuration and integration matchers keep HOSPITAL_ADMIN")
    void labConfigurationMatchersKeepTheRole() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        for (String matcher : new String[] {
            "API_LAB_TEST_DEFINITIONS, API_LAB_TEST_DEFINITIONS_PATTERN)",
            ".requestMatchers(HttpMethod.GET,  API_LAB_QC_EVENTS, API_LAB_QC_EVENTS_PATTERN)",
            ".requestMatchers(HttpMethod.GET,  API_LAB_REFLEX_RULES, API_LAB_REFLEX_RULES_PATTERN)",
            "API_LAB_HL7, API_LAB_HL7_PATTERN)",
            ".requestMatchers(HttpMethod.GET, API_LAB_INSTRUMENT_OUTBOX, API_LAB_INSTRUMENT_OUTBOX_PATTERN)"}) {
            assertThat(rolesOf(source, matcher)).as(matcher).contains("ROLE_HOSPITAL_ADMIN");
        }
    }
}
