package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1930 (r54): signing an addendum stored it FINAL, so the chart timeline
 * showed only the addendum's text and the card and the record PDF showed it
 * as a second conclusion. A report keeps its kind (an original read, or an
 * ADDENDUM / CORRECTED / AMENDED revision) through every status write.
 *
 * <ul>
 *   <li>No main code writes a literal FINAL onto a report: the signed status
 *       is chosen in one place, {@code ImagingReportServiceImpl.signedStatusOf}.</li>
 *   <li>Every method that writes a report's status is listed here with the
 *       check that keeps the kind; a new writer fails until it is.</li>
 * </ul>
 */
@DisplayName("An imaging report keeps its kind through every status write")
class ImagingReportKindGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern LITERAL_FINAL_WRITE = Pattern.compile(
        "setReportStatus\\(\\s*+(?:[\\w.]*?\\.)?ImagingReportStatus\\.FINAL\\s*+\\)"
            + "|appendStatusHistory\\([^;]*?ImagingReportStatus\\.FINAL"
            + "|ImagingReport\\.builder\\(\\)[^;]*?\\.reportStatus\\(\\s*+(?:[\\w.]*?\\.)?ImagingReportStatus\\.FINAL");
    private static final Pattern STATUS_WRITE = Pattern.compile("\\.setReportStatus\\(");
    private static final Pattern DECLARATION = Pattern.compile(
        "\\n\\s++(?:(?:public|private|protected|static|final|synchronized)\\s++)++(?:[\\w<>\\[\\]?.]|,\\s*+)++\\s++(\\w++)\\s*+\\(");

    /** Each method that writes a report status, and the text in its body that keeps the kind. */
    private static final Map<String, String> KIND_KEEPERS = Map.of(
        // A signature: a revision keeps its kind, an original read becomes FINAL.
        "ImagingReportServiceImpl#signReport", "signedStatusOf(",
        // An authored status: an edit may not cross between an original read and a revision.
        "ImagingReportServiceImpl#applyAuthoredStatus", "REVISION_STATUSES.contains(report.getReportStatus()) != REVISION_STATUSES.contains(requested)",
        // Cancel / error of an UNSIGNED read only: an administrative outcome, not a kind.
        "ImagingReportServiceImpl#updateReportStatus", "imaging.report.signedNoVoid");

    @Test
    void everyAuthoringPathUsesTheLocalizedStatusRefusal() throws IOException {
        String code = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/impl/ImagingReportServiceImpl.java"), StandardCharsets.UTF_8));
        for (String method : List.of("createReport", "updateReport")) {
            assertThat(SourceScan.methodBody(code, method)).contains("applyAuthoredStatus(");
        }
        assertThat(SourceScan.methodBody(code, "applyAuthoredStatus"))
            .contains("\"imaging.report.authoredStatus.invalid\"");
        String pdf = SourceScan.stripComments(Files.readString(MAIN.resolve("service/PatientRecordPdfService.java")));
        assertThat(SourceScan.methodBody(pdf, "imagingConclusions")).contains("identifiedImagingConclusion(");
        assertThat(SourceScan.methodBody(pdf, "identifiedImagingConclusion"))
            .contains("getReportStatus()", "getReportVersion()", "getSignedAt()", "getCreatedAt()", "getImpression()");
    }

    @Test
    void noMainCodeWritesALiteralFinal() throws IOException {
        List<String> offenders = new ArrayList<>();
        scan((file, code) -> {
            Matcher m = LITERAL_FINAL_WRITE.matcher(code);
            while (m.find()) {
                offenders.add(file + ":" + SourceScan.lineOf(code, m.start()));
            }
        });
        assertThat(offenders)
            .as("a report is written FINAL directly (QA D1930: a signed addendum became a second conclusion); "
                + "choose the signed status with ImagingReportServiceImpl.signedStatusOf" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void everyReportStatusWriterKeepsTheKind() throws IOException {
        Map<String, String> writers = new TreeMap<>();
        scan((file, code) -> {
            if (!code.contains("ImagingReport")) {
                return;
            }
            Matcher m = STATUS_WRITE.matcher(code);
            while (m.find()) {
                String method = enclosingMethod(code, m.start());
                String key = file.replace(".java", "") + "#" + method;
                writers.put(key, SourceScan.methodBody(code, method));
            }
        });
        // Report status writes outside the report service (the DTO copies in mappers) are not on the entity.
        writers.keySet().removeIf(k -> k.contains("Mapper#") || k.contains("Dto") || k.contains("DTO"));
        assertThat(writers.keySet())
            .as("a method writes an imaging report's status: name the check that keeps the report's kind in "
                + "KIND_KEEPERS (QA D1930)" + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(KIND_KEEPERS.keySet());
        List<String> lost = new ArrayList<>();
        KIND_KEEPERS.forEach((writer, keeper) -> {
            String body = writers.get(writer);
            if (body == null || !body.contains(keeper)) {
                lost.add(writer + " no longer contains " + keeper);
            }
        });
        assertThat(lost).as("a report status writer lost the check that keeps the kind (QA D1930)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    void theScanCatchesTheD1930Shape() {
        String before = """
            class ImagingReportServiceImpl {
                public void signReport(UUID id) {
                    report.setReportStatus(ImagingReportStatus.FINAL);
                    appendStatusHistory(report, ImagingReportStatus.FINAL, "signed", signer, null);
                }
            }
            """;
        Matcher m = LITERAL_FINAL_WRITE.matcher(before);
        int hits = 0;
        while (m.find()) {
            hits++;
        }
        assertThat(hits).isEqualTo(2);
    }

    private interface Visitor {
        void visit(String file, String code);
    }

    private static void scan(Visitor visitor) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                visitor.visit(file.getFileName().toString(),
                    SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
    }

    private static String enclosingMethod(String code, int index) {
        Matcher m = DECLARATION.matcher(code);
        String name = null;
        while (m.find() && m.start() < index) {
            String candidate = m.group(1);
            if (!List.of("if", "for", "while", "switch", "catch", "return", "new").contains(candidate)) {
                name = candidate;
            }
        }
        return name;
    }
}
