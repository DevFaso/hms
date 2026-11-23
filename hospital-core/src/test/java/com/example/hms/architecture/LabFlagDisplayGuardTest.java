package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2640 (r65): a lab result's grade is the laboratory's,
 * {@code LabResultMapper#severityFlagOf} (or the mapped DTO's
 * {@code severityFlag}), as the lab results screen shows it. The row's stored
 * {@code abnormalFlag} is only what an analyser or a technologist recorded: it
 * is null when the test's reference range grades the value, so a screen that
 * shows it read a creatinine of 118 µmol/L on 62–106 with no flag (the
 * radiologist's reading context), or « à revoir » (the staff snapshot).
 *
 * <p>Every main class that calls {@code getAbnormalFlag()} is listed here with
 * the reason it may: the grader itself, a fallback AFTER the graded severity,
 * a write path, or a DTO getter that already holds the graded value. A new
 * reader fails this test until it shows the lab's grade instead, or is listed
 * with a reason. This list may only shrink.
 */
class LabFlagDisplayGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Map<String, String> ALLOWED = Map.ofEntries(
        Map.entry("com/example/hms/mapper/LabResultMapper.java", "the grader: combines the range with the recorded flag"),
        Map.entry("com/example/hms/service/lab/PatientLabResultGrade.java",
            "patient status: the more severe of the mapper's grade and the recorded flag"),
        Map.entry("com/example/hms/service/CriticalValueNotificationService.java",
            "an analyser's CRITICAL pages, as the mapper grades it"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java",
            "write path, autoverification and in-basket fallback after severityFlagOf; reflex rule conditions"),
        Map.entry("com/example/hms/service/ResultReviewServiceImpl.java", "fallback after the graded severity (D1394)"),
        Map.entry("com/example/hms/service/impl/ChartReviewServiceImpl.java", "fallback after the graded severity (D1394)"),
        Map.entry("com/example/hms/service/PatientSnapshotServiceImpl.java",
            "direction fallback after the graded severity, as the chart"),
        Map.entry("com/example/hms/service/PatientRecordPdfService.java",
            "ChartReviewDTO.ResultEntryDTO getter: already the graded severity"),
        Map.entry("com/example/hms/utility/Hl7v2MessageBuilder.java", "outbound HL7 OBX-8 of the recorded flag")
    );

    @Test
    void everyStoredFlagReaderIsListed() throws IOException {
        Set<String> readers = readers();
        Set<String> unlisted = new TreeSet<>(readers);
        unlisted.removeAll(ALLOWED.keySet());
        assertThat(unlisted)
            .as("Show the laboratory's grade (LabResultMapper#severityFlagOf), not the stored abnormalFlag, "
                + "or list the class here with the reason (QA D2640)")
            .isEmpty();
    }

    @Test
    void theListOnlyNamesRealReaders() throws IOException {
        Set<String> stale = new TreeSet<>(ALLOWED.keySet());
        stale.removeAll(readers());
        assertThat(stale).as("these no longer read the stored flag: remove them so the list only shrinks").isEmpty();
    }

    @Test
    void theImagingReadingContextShowsTheLabGrade() throws IOException {
        String source = Files.readString(
            MAIN.resolve("com/example/hms/service/imaging/ImagingReadingContextService.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("severityFlagOf(").doesNotContain("getAbnormalFlag()");
    }

    private static Set<String> readers() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(".getAbnormalFlag()")) {
                    found.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return found;
    }
}
