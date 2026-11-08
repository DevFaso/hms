package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 33: a reader of a number out of a free-text dose sees a range's first number.
 * "2-4 mg" passed a limit of 3 mg; the FHIR export read "2 a 4" as 24 and "12,5" as 125.
 * A class that holds a dose's text and also parses text is listed here with what it does
 * with a range, so a new one is looked at instead of reading the low end by default.
 */
@DisplayName("every class that could read a number per dose says what a range does there")
class DoseNumberReaderGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    private static final Pattern HOLDS_A_DOSE_TEXT =
        Pattern.compile("\\.getDosage\\(\\)|String dosage\\b|\\.dosage\\(\\)");
    private static final Pattern PARSES_TEXT = Pattern.compile(
        "Pattern\\.compile|parseDouble|parseFloat|new (?:java\\.math\\.)?BigDecimal\\(|Double\\.valueOf"
            + "|\\.replaceAll\\(|\\.matches\\(|\\.split\\(");

    /** Class, and what a range does there. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
        Map.entry("cdshooks/rules/CdsRuleEngine.java", "reads DoseRange.highEnd(dosage): limits judge the high end"),
        Map.entry("cdshooks/rules/DoseRange.java", "the one place a range is read"),
        Map.entry("service/pharmacy/PrescribedQuantityEstimate.java",
            "matches the whole text as ONE amount: a range is null, and the fill waits for a recorded quantity"),
        Map.entry("fhir/mapper/MedicationRequestFhirMapper.java",
            "matches the whole text as ONE number: a range stays in the dosage's text"),
        Map.entry("service/AdmissionStayEncounterService.java", "copies the text; its parsing is about something else"),
        Map.entry("service/DischargeSummaryServiceImpl.java", "copies the text; its parsing is about something else"),
        Map.entry("service/EncounterServiceImpl.java", "copies the text; its parsing is about something else"),
        Map.entry("service/impl/MedicationHistoryServiceImpl.java", "copies the text; it parses the duration"),
        Map.entry("service/PatientServiceImpl.java", "copies the text; its parsing is about something else"),
        Map.entry("service/PatientSnapshotServiceImpl.java", "copies the text; its parsing is about something else"),
        Map.entry("service/PrescriptionServiceImpl.java", "hands the text to CdsRuleEngine; its parsing is about something else"));

    @Test
    void everyDoseReaderIsKnown() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        Set<String> found = new TreeSet<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (HOLDS_A_DOSE_TEXT.matcher(text).find() && PARSES_TEXT.matcher(text).find()) {
                found.add(ROOT.relativize(source).toString().replace('\\', '/'));
            }
        }
        assertThat(found).as("go through DoseRange.highEnd, or say here what a range does").isEqualTo(new TreeSet<>(KNOWN.keySet()));
    }

    @Test
    void theEngineReadsThroughTheRange() throws IOException {
        String engine = Files.readString(ROOT.resolve("cdshooks/rules/CdsRuleEngine.java"), StandardCharsets.UTF_8);
        assertThat(engine).contains("String read = DoseRange.highEnd(dosage);")
            .contains("DOSE_NUMBER.matcher(DoseRange.highEnd(dosage))");
    }
}
