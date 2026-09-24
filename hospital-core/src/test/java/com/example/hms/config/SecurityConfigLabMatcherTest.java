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

    /**
     * B8 — the coarse matcher is first-match-wins and terminal, so every role
     * the controller's {@code @PreAuthorize} admits must be admitted here too;
     * LAB_DIRECTOR, QUALITY_MANAGER and SUPER_ADMIN used to get 403 before the
     * annotation that permits them ever ran.
     */
    @Test
    @DisplayName("POST /lab-results matcher admits every LabResultAuthority.ENTRY_EXPRESSION role")
    void resultEntryMatcherCoversTheAnnotation() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String matcherRoles = rolesOf(source, ".requestMatchers(HttpMethod.POST, API_LAB_RESULTS)");

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z_]+)'")
            .matcher(com.example.hms.service.lab.LabResultAuthority.ENTRY_EXPRESSION);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(matcherRoles).as("POST /lab-results admits %s", m.group(1))
                .contains("ROLE_" + m.group(1));
        }
        assertThat(seen).as("the annotation names at least one role").isPositive();
    }

    @Test
    @DisplayName("PUT /lab-results has no narrower matcher than the annotation")
    void resultUpdateHasNoNarrowerMatcher() throws IOException {
        // There is deliberately no PUT matcher for lab results: the path falls
        // through to the authenticated catch-all and the controller annotation
        // decides. A PUT matcher naming fewer roles would reopen B8 for updates.
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(source).doesNotContain(".requestMatchers(HttpMethod.PUT, API_LAB_RESULTS");
    }

    /**
     * The same first-match-wins trap as B8, on the HL7 ingest door. The
     * matcher admitted only HOSPITAL_ADMIN and SUPER_ADMIN while the
     * controller's annotation names three lab roles as well, so the accounts
     * the endpoint exists for were answered 403 in the filter chain and the
     * annotation never ran.
     *
     * <p>Read out of the controller's own source rather than restated here,
     * so widening the annotation without widening the matcher fails this test
     * instead of shipping a door nobody can open.
     */
    @Test
    @DisplayName("POST /lab/hl7 matcher admits every role Hl7InboundController's annotation names")
    void hl7IngestMatcherCoversTheAnnotation() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String matcherRoles = rolesOf(source, "API_LAB_HL7, API_LAB_HL7_PATTERN)");

        String controller = Files.readString(
            Paths.get("src/main/java/com/example/hms/controller/Hl7InboundController.java"),
            StandardCharsets.UTF_8);
        int at = controller.indexOf("@PreAuthorize(");
        assertThat(at).as("the inbound handler carries an annotation").isPositive();
        String annotation = controller.substring(at, controller.indexOf(")", at));

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(annotation);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(matcherRoles).as("POST /lab/hl7 admits %s", m.group(1))
                .contains("ROLE_" + m.group(1));
        }
        assertThat(seen).as("the annotation names at least one role").isPositive();
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
