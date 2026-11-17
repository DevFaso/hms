package com.example.hms.i18n;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicalComparisonDisplayGuardTest {
    /** This task guards the at-least token (>=); other operators are outside its display-only scope. */
    private static List<String> programmingComparisonKeys(String source) throws java.io.IOException {
        var properties = new java.util.Properties();
        properties.load(new java.io.StringReader(source));
        return properties.stringPropertyNames().stream()
            .filter(key -> properties.getProperty(key).contains(">=")).sorted().toList();
    }

    @Test
    void everyTranslatedMessageUsesTheReadableComparison() throws Exception {
        var found = new ArrayList<String>();
        try (var paths = Files.walk(Path.of("src/main/resources/i18n"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".properties")).toList()) {
                for (var key : programmingComparisonKeys(Files.readString(path))) found.add(path + ":" + key);
            }
        }
        assertThat(found).as("generated display text must use ≥ instead of >=").isEmpty();
    }

    @Test
    void everyEnglishBpaVariantPreservesItsExistingThresholds() throws Exception {
        var source = TestMessageSources.bundles();
        var expected = java.util.Map.of(
            "NEWS2_EWS.summary", List.of("NEWS2 ≥ 5", "scoring 3"),
            "PREGNANCY_HYPERTENSION.summary", List.of("systolic ≥ 140", "diastolic ≥ 90"),
            "pregnancyHtn.severe", List.of("systolic ≥ 160", "diastolic ≥ 110"),
            "POSTPARTUM_SEVERE_HYPERTENSION.summary", List.of("systolic ≥ 160", "diastolic ≥ 110"),
            "ADULT_SEVERE_HYPERTENSION.summary", List.of("systolic ≥ 180", "diastolic ≥ 120"),
            "ADULT_SEVERE_HYPERTENSION.summaryUrgent", List.of("systolic ≥ 180", "diastolic ≥ 120"));
        expected.forEach((key, numbers) -> assertThat(source.getMessage("cds.bpa." + key, null, Locale.ENGLISH))
            .contains(numbers.toArray(String[]::new)).doesNotContain(">="));
        assertThat(programmingComparisonKeys("cds.bpa.example=NEWS2 >= 5")).containsExactly("cds.bpa.example");
        assertThat(programmingComparisonKeys("cds.bpa.example:NEWS2 >= 5")).containsExactly("cds.bpa.example");
        assertThat(programmingComparisonKeys("cds.bpa.example=NEWS2 \\\n            >= 5")).containsExactly("cds.bpa.example");
        assertThat(programmingComparisonKeys("cds.bpa.example=NEWS2 ≥ 5")).isEmpty();
        assertThat(programmingComparisonKeys("# example >= 5\n! note=a >= b")).isEmpty();
    }
}
