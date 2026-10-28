package com.example.hms.service.emar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D950: one reading of a medication route for display and for the five-rights check. */
class MedicationRoutesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "IV|IV", "iv|IV", "INTRAVENOUS|IV", "Intraveineuse|IV", "intraveineuse (IV)|IV", "Intravenosa|IV",
        "ORAL|PO", "per os|PO", "VO|PO", "Orale (PO)|PO", "SUBCUTANEOUS|SC", "sous-cutanée|SC", "SQ|SC",
        "Intramusculaire|IM", "rectale|PR", "Inhalée (INH)|INH", "nébulisation|NEB", "Topique|TOP"
    })
    void d950_readsCodesWordsAndFormLabels(String text, String code) {
        assertThat(MedicationRoutes.canonical(text)).isEqualTo(code);
    }

    @Test
    void d950_unknownTextIsNotARoute() {
        assertThat(MedicationRoutes.canonical("dans le thé")).isNull();
        assertThat(MedicationRoutes.canonical("")).isNull();
        assertThat(MedicationRoutes.canonical(null)).isNull();
    }

    /** QA D950 replay (u18.js): "IV" and "Intraveineuse" pass against a stored "INTRAVENOUS". */
    @Test
    void d950_theFiveRightsComparisonAcceptsEveryNameOfTheRoute() {
        assertThat(MedicationRoutes.sameRoute("INTRAVENOUS", "IV")).isTrue();
        assertThat(MedicationRoutes.sameRoute("INTRAVENOUS", "Intraveineuse")).isTrue();
        assertThat(MedicationRoutes.sameRoute("INTRAVENOUS", "INTRAVENOUS")).isTrue();
        assertThat(MedicationRoutes.sameRoute("INTRAVENOUS", "IM")).isFalse();
        assertThat(MedicationRoutes.sameRoute("dans le thé", "DANS LE THÉ")).isTrue();
    }

    /**
     * QA D950 guard: every route code the prescribing form offers resolves to
     * itself, and nothing in the backend compares a route by its text any
     * more: a {@code getRoute()} compared with equals/equalsIgnoreCase is how
     * "IV" came to fail against "INTRAVENOUS".
     */
    @Test
    void d950_noRouteIsComparedAsText() throws Exception {
        String form = Files.readString(Path.of("..", "hospital-portal", "src", "app", "prescriptions", "prescriptions.ts"));
        Matcher codes = Pattern.compile("readonly routes: readonly string\\[] = \\[([^\\]]+)]").matcher(form);
        assertThat(codes.find()).isTrue();
        for (String code : codes.group(1).replace("'", "").split(",")) {
            assertThat(MedicationRoutes.canonical(code.trim())).as(code).isEqualTo(code.trim());
        }

        Pattern textCompare = Pattern.compile(
            "getRoute\\(\\)\\)?\\s*\\.\\s*(trim\\(\\)\\s*\\.\\s*)?(equals|equalsIgnoreCase)\\(|"
                + "\\.(equals|equalsIgnoreCase)\\([^)]*getRoute\\(\\)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (textCompare.matcher(text).find()) {
                    offenders.add(file.toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
