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
 * QA D1082 guard (round 18): a patient's "current" record that does not exist
 * yet answered 404. Every chart of a woman with no maternal history sent
 * GET …/maternal-history/patient/{id}/current and logged a failed request; the
 * admissions "current" endpoint did the same for every patient not in a bed.
 * None is an answer: the house convention (birth plan, high-risk care plan,
 * eligibility "latest") is 204 No Content.
 *
 * <p>Every {@code @GetMapping} on a patient's {@code …/current},
 * {@code …/active} or {@code …/latest} record must answer an empty result with
 * {@code noContent()} (or return an {@code Optional} body), never
 * {@code notFound()}; and the service it calls for it must not throw a
 * not-found when the patient has none (checked for the three D1082 sites).
 */
@DisplayName("An empty patient 'current' record answers 204, never 404")
class EmptyCurrentRecordAnswerGuardTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/example/hms/controller");
    private static final Pattern PATIENT_CURRENT = Pattern.compile(
        "@GetMapping\\(\\s*+(?:value\\s*+=\\s*+)?+\"([^\"]*+)\"\\s*+\\)");

    @Test
    @DisplayName("every patient current/active/latest GET answers an empty result with 204")
    void emptyCurrentIsNoContent() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CONTROLLERS)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        List<String> offenders = new ArrayList<>();
        int endpoints = 0;
        for (Path file : files) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            List<String> found = handlers(source);
            endpoints += found.size();
            for (String handler : found) {
                if (!answersEmptyWithNoContent(handler)) {
                    offenders.add(file.getFileName() + ": " + firstLine(handler));
                }
            }
        }
        assertThat(endpoints).as("patient current/active/latest endpoints").isGreaterThanOrEqualTo(6);
        assertThat(offenders)
            .as("a patient's current/active/latest record answers 404 (or a 200 with an empty body) when there is "
                + "none, and every chart logs a failed request (QA D1082): return ResponseEntity.noContent()"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1082 shapes")
    void catchesTheOldShapes() {
        String notFound = """
            @GetMapping("/patient/{patientId}/current")
            public ResponseEntity<AdmissionResponseDTO> current(@PathVariable UUID patientId) {
                AdmissionResponseDTO admission = admissionService.getCurrentAdmissionForPatient(patientId);
                if (admission == null) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(admission);
            }
            """;
        assertThat(handlers(notFound)).hasSize(1);
        assertThat(answersEmptyWithNoContent(handlers(notFound).get(0))).isFalse();
        String plainOk = """
            @GetMapping("/patient/{patientId}/current")
            public ResponseEntity<MaternalHistoryResponseDTO> current(@PathVariable UUID patientId) {
                return ResponseEntity.ok(service.getCurrentMaternalHistoryByPatientId(patientId, user));
            }
            """;
        assertThat(answersEmptyWithNoContent(handlers(plainOk).get(0))).isFalse();
        String fixed = plainOk.replace("return ResponseEntity.ok(service.getCurrentMaternalHistoryByPatientId(patientId, user));",
            "var r = service.getCurrentMaternalHistoryByPatientId(patientId, user);\n"
                + "return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);");
        assertThat(answersEmptyWithNoContent(handlers(fixed).get(0))).isTrue();
    }

    /** A patient's {@code …{patientId}…/current}, {@code /active} or {@code /latest} path. */
    static boolean isPatientCurrentPath(String path) {
        int last = path.lastIndexOf('/');
        return last >= 0 && path.substring(0, last).contains("{patientId}")
            && java.util.Set.of("current", "active", "latest").contains(path.substring(last + 1));
    }

    static List<String> handlers(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = PATIENT_CURRENT.matcher(source);
        while (m.find()) {
            if (!isPatientCurrentPath(m.group(1))) {
                continue;
            }
            int open = source.indexOf('{', source.indexOf(')', source.indexOf("public", m.end())));
            if (open < 0) {
                continue;
            }
            int close = SourceScan.closingBrace(source, open);
            out.add(source.substring(m.start(), Math.min(close + 1, source.length())));
        }
        return out;
    }

    static boolean answersEmptyWithNoContent(String handler) {
        if (handler.contains("notFound()")) {
            return false;
        }
        return handler.contains("noContent()") || handler.matches("(?s).*ResponseEntity<\\s*Optional<.*");
    }

    private static String firstLine(String handler) {
        int nl = handler.indexOf('\n');
        return nl < 0 ? handler : handler.substring(0, nl);
    }
}
