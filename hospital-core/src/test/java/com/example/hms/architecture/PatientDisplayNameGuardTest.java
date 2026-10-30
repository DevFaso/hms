package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1036 / D926 (class guard, server half): a patient's name is sent whole.
 * The appointment, the reception queue, the snapshot and the e-mails built it
 * as {@code first + " " + last}, so a middle name captured at registration was
 * never shown. {@code Patient.getFullName()} is the one assembly (each part
 * once, single-spaced). A name MATCH is not a display ({@code nameContains(…)}
 * keeps "first last" so a search without the middle name still finds the
 * patient), and is skipped.
 *
 * <p>The portal half is {@code scripts/lib/person-name-display.test.mjs}.
 */
class PatientDisplayNameGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern FIRST_PLUS_LAST = Pattern.compile(
        "\\b(p|patient\\w*)(\\(\\))?\\.getFirstName\\(\\)\\s*\\+\\s*\" \"\\s*\\+\\s*\\1(\\(\\))?\\.getLastName\\(\\)"
            + "|getFullName\\(\\s*(p|patient\\w*)\\.getFirstName\\(\\),\\s*\\4\\.getLastName\\(\\)\\s*\\)");

    /** Sites in files other fix branches own, each with its defect id: they shrink, never grow. */
    private static final Map<String, GuardAllowlist.Allowance> ALLOWED = GuardAllowlist.allow(
        "bootstrap/DevSyntheticDataSeeder.java", 1, "dev fixture text, never shown to a reader",
        "service/ClinicalDashboardServiceImpl.java", 1, "QA D1036 class; fix/r17-clinical-workflow owns the file",
        "service/DoctorWorklistServiceImpl.java", 3, "QA D1036 class; fix/r17-clinical-workflow owns the file",
        "service/LabResultServiceImpl.java", 1, "QA D1036 class; fix/r17-lab-pharmacy-billing owns the file",
        "service/PatientFlowServiceImpl.java", 2, "QA D1036 class, pre-existing (patient flow board)",
        "service/PatientSnapshotServiceImpl.java", 1, "QA D1036 class, pre-existing (clinical snapshot)",
        "service/ResultReviewServiceImpl.java", 1, "QA D1036 class, pre-existing (result review)",
        "service/impl/PatientPortalServiceImpl.java", 2, "QA D1036 class, pre-existing (portal)",
        "service/scheduling/PatientRecallServiceImpl.java", 1, "QA D1036 class, pre-existing (recalls)");

    @Test
    void aPatientsNameIsAssembledOnlyByGetFullName() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = FIRST_PLUS_LAST.matcher(src);
                while (m.find()) {
                    int lineStart = src.lastIndexOf('\n', m.start()) + 1;
                    String before = src.substring(lineStart, m.start());
                    if (before.contains("nameContains(")) {
                        continue; // a match, not a display
                    }
                    sites.add(rel);
                }
            }
        }
        Map<String, Integer> actual = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a patient name built as first + last, dropping the middle name (QA D1036): use "
                + "Patient.getFullName(); " + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: lower its allowance").isEmpty();
    }

    @Test
    void theScanSeesTheShapes() {
        assertThat(FIRST_PLUS_LAST.matcher("p.getFirstName() + \" \" + p.getLastName()").find()).isTrue();
        assertThat(FIRST_PLUS_LAST.matcher("getFullName(patient.getFirstName(), patient.getLastName())").find())
            .isTrue();
        assertThat(FIRST_PLUS_LAST.matcher("staff.getUser().getFirstName() + \" \" + staff.getUser().getLastName()")
            .find()).isFalse();
    }
}
