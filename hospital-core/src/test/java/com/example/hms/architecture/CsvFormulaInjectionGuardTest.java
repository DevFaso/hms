package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 4, guard for the class "a CSV a person opens in a spreadsheet runs a cell as a formula"
 * (CSV injection, OWASP). A file that writes CSV ({@code new CSVPrinter(} or a {@code "text/csv"} response) must
 * write its text cells through {@code CsvCells.safe(}, or hand the writing to a file that does
 * ({@link #DELEGATES}). The writers that predate the rule are listed in {@link #LEGACY}: the list only shrinks
 * (a listed file that is fixed, or no longer writes CSV, fails this test until it is taken off the list).
 */
@DisplayName("Every CSV writer neutralises formula cells")
class CsvFormulaInjectionGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern WRITES_CSV = Pattern.compile("new CSVPrinter\\(|\"text/csv");
    private static final String SAFE_CALL = "CsvCells.safe(";

    /** A file that serves a CSV another file writes, with that writer. */
    private static final Map<String, String> DELEGATES = Map.of(
        "controller/PriceListController.java", "service/billing/PriceListCsv.java");

    /** CSV writers written before the rule (standing debt, tasklist): to be moved onto CsvCells.safe. */
    private static final Set<String> LEGACY = Set.of(
        "controller/LabTestDefinitionController.java",
        "controller/RoleController.java",
        "controller/SuperAdminAuditSearchController.java",
        "controller/pharmacy/PharmacyClaimController.java",
        "service/reporting/ReportGenerationService.java",
        "service/reporting/ScheduledReportService.java");

    @Test
    void everyCsvWriterEscapesFormulaCells() throws IOException {
        Set<String> unsafe = new TreeSet<>();
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!WRITES_CSV.matcher(code).find()) {
                    continue;
                }
                writers.add(rel);
                String writer = DELEGATES.getOrDefault(rel, rel);
                String writerCode = withoutComments(Files.readString(MAIN.resolve(writer), StandardCharsets.UTF_8));
                if (!writerCode.contains(SAFE_CALL)) {
                    unsafe.add(rel);
                }
            }
        }
        assertThat(unsafe).as("CSV writers that do not write their cells through CsvCells.safe")
            .isEqualTo(new TreeSet<>(LEGACY));
        assertThat(writers).as("the price-list export is a CSV writer this guard sees")
            .contains("controller/PriceListController.java", "service/billing/PriceListCsv.java");
    }

    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
