package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 4, guard for the class "a CSV a person opens in a spreadsheet runs a cell as a formula"
 * (CSV injection, OWASP).
 *
 * <ol>
 *   <li>A file that produces CSV ({@code new CSVPrinter(}, a {@code text/csv} media type written either way, or a
 *       {@code .csv} file name) writes it itself or names the files that do in {@link #DELEGATES}; a producer that
 *       writes no cell and is not mapped fails, so a new export cannot hide behind a service.</li>
 *   <li>Each writer escapes PER CELL: every {@code printRecord(} hands its values through {@code CsvCells.safeAll(},
 *       and every hand-written quoting helper (one that doubles {@code "} as {@code ""}) runs its value through
 *       {@code CsvCells.safe(}.</li>
 * </ol>
 *
 * The list of writers that do not ({@link #UNESCAPED}) is empty and stays so.
 */
@DisplayName("Every CSV writer neutralises formula cells, cell by cell")
class CsvFormulaInjectionGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    /** A file that produces CSV, whoever writes the bytes. */
    private static final Pattern PRODUCES_CSV = Pattern.compile(
        "new CSVPrinter\\(|\"text/csv|\"text\",\\s*+\"csv\"|\\.csv\"");
    /** A quoting helper's tell: the quote character doubled. */
    private static final Pattern QUOTE_DOUBLING = Pattern.compile("replace\\(\"\\\\\"\", \"\\\\\"\\\\\"\"\\)");
    private static final Pattern PRINT_RECORD = Pattern.compile("\\.printRecord\\(\\s*+(CsvCells\\.safeAll\\()?+");
    private static final Pattern METHOD_HEADER = Pattern.compile(
        "\\n\\s*+(?:(?:public|private|protected|static|final)\\s++)++[\\w<>\\[\\], ]++\\(");
    private static final String SAFE_CALL = "CsvCells.safe(";

    /** A file that serves a CSV other files write, with those writers. */
    private static final Map<String, List<String>> DELEGATES = Map.of(
        "controller/PriceListController.java", List.of("service/billing/PriceListCsv.java"),
        "service/billing/PriceListImportService.java", List.of("service/billing/PriceListCsv.java"),
        "controller/pharmacy/ControlledRegisterController.java",
            List.of("service/pharmacy/ControlledDrugRegisterService.java"),
        "controller/pharmacy/PharmacyClaimController.java",
            List.of("service/pharmacy/PharmacyClaimExportService.java"),
        "controller/RoleController.java", List.of("service/RoleServiceImpl.java"),
        "controller/SuperAdminAuditSearchController.java",
            List.of("service/impl/SuperAdminAuditSearchServiceImpl.java"),
        // Attaches the report the generator wrote; it writes no cell itself.
        "service/reporting/ScheduledReportService.java", List.of("service/reporting/ReportGenerationService.java"));

    /** Writers that do not escape cell by cell. Empty: a new entry is never the way to pass. */
    private static final Set<String> UNESCAPED = Set.of();

    @Test
    void everyCsvWriterEscapesEachCell() throws IOException {
        Set<String> unsafe = new TreeSet<>();
        Set<String> producers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (!PRODUCES_CSV.matcher(code(file)).find()) {
                    continue;
                }
                producers.add(rel);
                for (String writer : DELEGATES.getOrDefault(rel, List.of(rel))) {
                    unsafe.addAll(findings(writer, code(MAIN.resolve(writer))));
                }
            }
        }
        assertThat(unsafe).as("CSV writers that do not escape each cell through CsvCells (CSV injection)")
            .isEqualTo(new TreeSet<>(UNESCAPED));
        assertThat(producers).as("the guard sees the known CSV producers")
            .contains("controller/PriceListController.java", "controller/LabTestDefinitionController.java",
                "controller/pharmacy/ControlledRegisterController.java", "controller/RoleController.java",
                "controller/SuperAdminAuditSearchController.java", "controller/pharmacy/PharmacyClaimController.java",
                "service/reporting/ReportGenerationService.java");
        assertThat(DELEGATES.keySet()).as("every delegate entry is a producer the scan still finds")
            .allMatch(producers::contains);
    }

    /** What is wrong with one writer: no cell written at all, a raw printRecord, a quoting helper without CsvCells. */
    static List<String> findings(String rel, String code) {
        List<String> out = new ArrayList<>();
        Matcher printCall = PRINT_RECORD.matcher(code);
        boolean writes = false;
        while (printCall.find()) {
            writes = true;
            if (printCall.group(1) == null) {
                out.add(rel + ":" + lineOf(code, printCall.start()) + " printRecord without CsvCells.safeAll");
            }
        }
        Matcher doubling = QUOTE_DOUBLING.matcher(code);
        while (doubling.find()) {
            writes = true;
            if (!enclosingMethod(code, doubling.start()).contains(SAFE_CALL)) {
                out.add(rel + ":" + lineOf(code, doubling.start()) + " quoting helper without CsvCells.safe");
            }
        }
        if (!writes) {
            out.add(rel + " writes no CSV cell: map its producer to the real writer in DELEGATES");
        }
        return out;
    }

    @Test
    @DisplayName("the scan itself: a raw printRecord, a helper without CsvCells and an unmapped producer are findings")
    void theScanFindsTheClass() {
        assertThat(findings("A.java", "void w(){ printer.printRecord(name, 2); }")).hasSize(1);
        assertThat(findings("A.java", "void w(){ printer.printRecord(CsvCells.safeAll(name, 2)); }")).isEmpty();
        assertThat(findings("A.java", """
            private String esc(String v) {
                return "\\"" + v.replace("\\"", "\\"\\"") + "\\"";
            }
            """)).hasSize(1);
        assertThat(findings("A.java", """
            private String esc(String v) {
                String s = CsvCells.safe(v);
                return "\\"" + s.replace("\\"", "\\"\\"") + "\\"";
            }
            """)).isEmpty();
        assertThat(findings("A.java", "byte[] b = service.export();")).hasSize(1);
        assertThat(PRODUCES_CSV.matcher("new MediaType(\"text\", \"csv\", UTF_8)").find()).isTrue();
        assertThat(PRODUCES_CSV.matcher("\"registre-\" + from + \".csv\"").find()).isTrue();
    }

    private static String code(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The body of the method around {@code index}: from the last method header before it to the next one. */
    private static String enclosingMethod(String code, int index) {
        Matcher m = METHOD_HEADER.matcher(code);
        int start = 0;
        int end = code.length();
        while (m.find()) {
            if (m.start() < index) {
                start = m.start();
            } else {
                end = m.start();
                break;
            }
        }
        return code.substring(start, end);
    }
}
