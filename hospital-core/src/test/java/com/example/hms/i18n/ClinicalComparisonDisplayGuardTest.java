package com.example.hms.i18n;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicalComparisonDisplayGuardTest {
    private static boolean hasProgrammingComparison(String line) {
        return !line.stripLeading().startsWith("#") && line.contains("=") && line.substring(line.indexOf('=') + 1).contains(">=");
    }

    @Test
    void everyTranslatedMessageUsesTheReadableComparison() throws Exception {
        var found = new ArrayList<String>();
        try (var paths = Files.walk(Path.of("src/main/resources/i18n"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".properties")).toList()) {
                for (var line : Files.readAllLines(path)) if (hasProgrammingComparison(line)) found.add(path + ":" + line);
            }
        }
        assertThat(found).as("generated display text must use ≥ instead of >=").isEmpty();
    }

    @Test
    void everyEnglishBpaVariantPreservesItsExistingThresholds() {
        var source = TestMessageSources.bundles();
        var expected = java.util.Map.of(
            "NEWS2_EWS.summary", List.of("≥ 5", "scoring 3"),
            "PREGNANCY_HYPERTENSION.summary", List.of("≥ 140", "≥ 90"),
            "pregnancyHtn.severe", List.of("≥ 160", "≥ 110"),
            "POSTPARTUM_SEVERE_HYPERTENSION.summary", List.of("≥ 160", "≥ 110"),
            "ADULT_SEVERE_HYPERTENSION.summary", List.of("≥ 180", "≥ 120"),
            "ADULT_SEVERE_HYPERTENSION.summaryUrgent", List.of("≥ 180", "≥ 120"));
        expected.forEach((key, numbers) -> assertThat(source.getMessage("cds.bpa." + key, null, Locale.ENGLISH))
            .contains(numbers.toArray(String[]::new)).doesNotContain(">="));
        assertThat(hasProgrammingComparison("cds.bpa.example=NEWS2 >= 5")).isTrue();
        assertThat(hasProgrammingComparison("cds.bpa.example=NEWS2 ≥ 5")).isFalse();
        assertThat(hasProgrammingComparison("# example >= 5")).isFalse();
    }
}
