package com.example.hms.cdshooks;

import com.example.hms.cdshooks.service.MedicationDraftExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-01: a FHIR draft's route, as the rule engine receives it. */
class MedicationDraftExtractorRouteTest {

    private static Map<String, Object> draft(Map<String, Object> route) {
        return Map.of("dosageInstruction", List.of(Map.of("text", "1 g", "route", route)));
    }

    @Test
    void theTextIsTheRoute() {
        assertThat(MedicationDraftExtractor.routeOf(draft(Map.of("text", "Oral")))).isEqualTo("Oral");
    }

    @Test
    void aCodingTheRouteReadingKnowsWinsOverOneItDoesNot() {
        assertThat(MedicationDraftExtractor.routeOf(draft(Map.of("coding", List.of(
            Map.of("code", "99999"), Map.of("code", "47625008", "display", "Intravenous")))))).isEqualTo("Intravenous");
    }

    @Test
    void aCodingNothingKnowsStaysAsWrittenAndIsNeverReadAsOral() {
        assertThat(MedicationDraftExtractor.routeOf(draft(Map.of("coding", List.of(Map.of("code", "99999"))))))
            .isEqualTo("99999");
    }

    @Test
    void aDraftWithNoRouteStatesNone() {
        assertThat(MedicationDraftExtractor.routeOf(Map.of("dosageInstruction", List.of(Map.of("text", "1 g"))))).isNull();
        assertThat(MedicationDraftExtractor.routeOf(Map.of())).isNull();
        assertThat(MedicationDraftExtractor.routeOf(null)).isNull();
        assertThat(MedicationDraftExtractor.extract(Map.of("dosageInstruction", List.of(Map.of(
            "text", "1 g", "route", Map.of("text", "PO"))))).route()).isEqualTo("PO");
    }
}
