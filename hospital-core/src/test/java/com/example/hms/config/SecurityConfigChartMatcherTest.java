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
 * E9 #67 (D5) — the matcher layer agrees with the annotations: the chart
 * sub-resources of /patients are matched ahead of the /patients/** blanket
 * and never admit HOSPITAL_ADMIN; vitals writes and reads do not either.
 * The filter chain is first-match-wins, so an annotation-only cut would have
 * left the blanket admitting the role at the edge.
 */
class SecurityConfigChartMatcherTest {

    private static final Path SOURCE = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");

    @Test
    @DisplayName("the chart patterns cover every clinical sub-resource of a patient")
    void chartPatternsCoverTheChart() {
        assertThat(SecurityConfig.API_PATIENT_CHART_PATTERNS).contains(
            "/patients/*/allergies", "/patients/*/diagnoses", "/patients/*/chart-updates",
            "/patients/*/storyboard", "/patients/*/chart-review", "/patients/*/lab-results",
            "/patients/*/medications", "/patients/*/micro-cultures", "/patients/*/fhir-record",
            "/patients/*/growth-chart", "/patients/*/intake-output");
    }

    @Test
    @DisplayName("the chart matcher sits ahead of the /patients/** blanket and refuses HOSPITAL_ADMIN")
    void chartMatcherAheadOfTheBlanketWithoutTheRole() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int chart = source.indexOf(".requestMatchers(HttpMethod.GET, API_PATIENT_CHART_PATTERNS)");
        int blanket = source.indexOf(".requestMatchers(HttpMethod.GET, API_PATIENTS, API_PATIENTS_PATTERN)");
        assertThat(chart).as("chart matcher declared").isPositive();
        assertThat(blanket).as("blanket matcher declared").isPositive();
        assertThat(chart).as("chart matcher precedes the blanket (first match wins)").isLessThan(blanket);
        String chartRoles = source.substring(chart, source.indexOf(")", source.indexOf(".hasAnyAuthority(", chart)));
        assertThat(chartRoles).doesNotContain("ROLE_HOSPITAL_ADMIN").doesNotContain("ROLE_ADMIN,");
    }

    @Test
    @DisplayName("vitals matchers do not admit HOSPITAL_ADMIN on either verb")
    void vitalsMatchersWithoutTheRole() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        for (String verb : new String[] {"POST", "GET"}) {
            int at = source.indexOf(".requestMatchers(HttpMethod." + verb + ", API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)");
            assertThat(at).as("%s vitals matcher declared", verb).isPositive();
            String roles = source.substring(at, source.indexOf(")", source.indexOf(".hasAnyAuthority(", at)));
            assertThat(roles).as("%s vitals matcher", verb).doesNotContain("ROLE_HOSPITAL_ADMIN");
        }
    }
}
