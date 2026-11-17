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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1100 guard (round 19): an inpatient's bed freed by hand with no reason
 * and no trace. "Retirer le lit" fired {@code DELETE /admissions/{id}/bed} in
 * one click; the patient vanished from the bed board.
 *
 * <p>Every production method that detaches a bed from a stay by hand
 * ({@code bedAssignmentService.unassignBed(…)}) must refuse a blank reason
 * (a {@code …ReasonRequired} key) and audit the release
 * ({@code AuditEventType.ADMISSION_BED_RELEASED}). The releases that end the
 * stay itself (discharge, cancellation, transfer, death: {@code releaseBed})
 * are recorded by those flows and are not this class. No allowlist.
 */
@DisplayName("A bed freed by hand states why and is audited")
class BedReleaseReasonGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");

    @Test
    @DisplayName("every manual bed release requires a reason and audits it")
    void everyManualBedReleaseStatesItsReason() throws IOException {
        List<String> releases = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String cls = file.getFileName().toString().replace(".java", "");
                if ("BedAssignmentService".equals(cls)) {
                    continue; // the primitive itself
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String[] site : offenders(cls, source)) {
                    releases.add(site[0]);
                    if (site[1] != null) {
                        offenders.add(site[0] + ": " + site[1]);
                    }
                }
            }
        }
        assertThat(releases).as("the manual bed releases found").contains("AdmissionServiceImpl#unassignBed");
        assertThat(offenders)
            .as("a bed freed by hand without a required reason or without its audit row (QA D1100)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the round-19 shape")
    void catchesTheOldShape() {
        String before = """
            class AdmissionServiceImpl {
                public AdmissionResponseDTO unassignBed(UUID admissionId) {
                    Admission admission = loadTenantScopedAdmission(admissionId);
                    bedAssignmentService.unassignBed(admission);
                    return map(admissionRepository.save(admission));
                }
            }
            """;
        List<String[]> sites = offenders("AdmissionServiceImpl", before);
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0)[1]).isNotNull();
    }

    /** {site, problem or null} for each method that calls bedAssignmentService.unassignBed. */
    static List<String[]> offenders(String cls, String source) {
        List<String[]> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (!body.contains("bedAssignmentService.unassignBed(")) {
                continue;
            }
            String site = cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1)));
            String problem = null;
            if (!body.matches("(?s).*ReasonRequired\".*")) {
                problem = "no required reason";
            } else if (!body.contains("auditBedRelease") && !body.contains("ADMISSION_BED_RELEASED")) {
                problem = "no audit of the release";
            }
            out.add(new String[] {site, problem});
        }
        return out;
    }
}
