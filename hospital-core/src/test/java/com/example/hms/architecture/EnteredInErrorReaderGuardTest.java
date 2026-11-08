package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1240 (round 25) guard: a diagnosis removed from the chart as entered in
 * error still showed on the patient's "Antécédents médicaux" page five times,
 * because the portal reader listed every problem row. The D1214 fix had
 * covered the active lists only.
 *
 * <p>Every method of production code that loads problem-list rows
 * ({@code …ProblemRepository.find…(}) must, in its own body, either leave out
 * a removed row ({@code isEnteredInError()}), read only live statuses (an
 * {@code ACTIVE} filter: a removed row is INACTIVE), or be listed below as a
 * staff reader that shows the removal on purpose. Every aggregate query of
 * {@code PatientProblemRepository} must exclude {@code enteredInError}.
 */
@DisplayName("A diagnosis removed as entered in error is left out of every patient-facing, proxy, FHIR and count reader")
class EnteredInErrorReaderGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** A field, parameter or local typed {@code PatientProblemRepository}: its name. */
    private static final Pattern PROBLEM_REPO_NAME = Pattern.compile("\\bPatientProblemRepository\\s++(\\w++)");

    /** The row is filtered for the removal, or only live statuses are read. */
    static final Pattern HANDLED = Pattern.compile(
        "isEnteredInError\\(\\)|ProblemStatus\\.ACTIVE\\b|ACTIVE_PROBLEM_STATUSES");

    private static final String STAFF = "QA D1240 audit (round 25): staff reader - ";

    /** {@code file#method} that reads removed rows on purpose. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "PatientServiceImpl#listPatientDiagnoses", 1,
            STAFF + "the chart's own history (includeHistorical) shows a removed row as INACTIVE with enteredInError",
        "PatientServiceImpl#loadPatientProblem", 1,
            STAFF + "loads one row for an edit or a removal (the removal refuses a removed row)",
        "VisitTimelineRows#diagnosisRows", 1,
            STAFF + "the clinicians' chronology lists every diagnosis with its status, a removal included",
        "ConditionFhirResourceProvider#read", 1,
            "a read by id answers the row marked verificationStatus=entered-in-error (ConditionFhirMapper)");

    @Test
    @DisplayName("every problem-list reader leaves out a removed row, or says why it shows it")
    void everyProblemReaderLeavesOutARemovedRow() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        int readers = 0;
        for (Path file : javaFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            java.util.Set<String> names = new java.util.TreeSet<>();
            Matcher n = PROBLEM_REPO_NAME.matcher(source);
            while (n.find()) {
                names.add(n.group(1));
            }
            if (names.isEmpty()) {
                continue;
            }
            Matcher m = Pattern.compile("\\b(?:" + String.join("|", names) + ")\\s*+\\.\\s*+find\\w*+\\(")
                .matcher(source);
            while (m.find()) {
                readers++;
                String body = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (!HANDLED.matcher(body).find()) {
                    actual.merge(file.getFileName().toString().replace(".java", "") + "#" + methodName(body), 1, Integer::sum);
                }
            }
        }
        assertThat(readers).as("the scan found the problem-list readers").isGreaterThan(8);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a reader of problem-list rows that lists a diagnosis removed as entered in error (QA D1240)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed or removed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("every aggregate of the problem list excludes the removed rows")
    void everyProblemCountExcludesTheRemovedRows() throws IOException {
        String repo = Files.readString(MAIN.resolve("repository/PatientProblemRepository.java"), StandardCharsets.UTF_8);
        Matcher q = Pattern.compile("@Query\\(\"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(repo);
        int queries = 0;
        while (q.find()) {
            queries++;
            assertThat(q.group(1)).as("a morbidity count that counts diagnoses removed as entered in error (QA D1240)")
                .contains("p.enteredInError = false");
        }
        assertThat(queries).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the scan catches the D1240 shape and accepts its fix")
    void catchesTheD1240Shape() {
        String before = """
            class S {
                public List<Dto> getMyMedicalHistory(Authentication auth) {
                    return patientProblemRepository.findByPatient_IdOrderByCreatedAtDesc(id).stream()
                        .map(S::toDto).toList();
                }
            }
            """;
        assertThat(HANDLED.matcher(AppointmentSlotRulesGuardTest.enclosingMethod(before, before.indexOf("patientProblem"))).find())
            .isFalse();
        String after = before.replace(".map(S::toDto)", ".filter(p -> !p.isEnteredInError()).map(S::toDto)");
        assertThat(HANDLED.matcher(AppointmentSlotRulesGuardTest.enclosingMethod(after, after.indexOf("patientProblem"))).find())
            .isTrue();
    }

    private static String methodName(String body) {
        int paren = body.indexOf('(');
        String head = body.substring(0, paren).trim();
        return head.substring(head.lastIndexOf(' ') + 1);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.startsWith(MAIN.resolve("repository")))
                .toList();
        }
    }
}
