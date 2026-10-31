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
 * QA D1099 guard (round 19): an examination in progress ended by someone who
 * is not examining. A midwife called {@code POST /encounters/{id}/complete-examination}
 * on a doctor's visit (200, READY_FOR_DISCHARGE, still assigned to him); the
 * tracker offered her "Terminer l'examen" and "Sortie".
 *
 * <p>Every service method that takes an encounter out of IN_PROGRESS to
 * AWAITING_RESULTS, READY_FOR_DISCHARGE or COMPLETED must call
 * {@code EncounterClosureRules.requireExaminingClinician} (or its
 * {@code …User} form), the mirror of the start rule (QA D1085). No allowlist.
 */
@DisplayName("Only the examining clinician ends an examination")
class ExaminingClinicianGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern ENDS_EXAM = Pattern.compile(
        "EncounterStatus\\.(?:READY_FOR_DISCHARGE|AWAITING_RESULTS|COMPLETED)\\b|EXAMINATION_ENDED");
    private static final Pattern RULE = Pattern.compile("requireExaminingClinician(?:User)?\\(");

    /*
     * Judged not this class (no IN_PROGRESS read there): ReceptionServiceImpl#updateEncounterStatus,
     * the flow board, admits a clinician only on their own patients ("You may only update encounter
     * status for your own patients", checked before the transition) and the desk never sets a clinical
     * status (EncounterClosureRules.requireDeskTransition).
     */

    @Test
    @DisplayName("every method that ends an examination in progress asks the examining-clinician rule")
    void everyExaminationEndIsTheExaminersOwn() throws IOException {
        Map<String, String> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String cls = file.getFileName().toString().replace(".java", "");
                for (String site : offenders(cls, source)) {
                    offenders.put(site, file.toString());
                }
            }
        }
        assertThat(offenders.keySet())
            .as("a method takes a visit out of IN_PROGRESS without EncounterClosureRules.requireExaminingClinician "
                + "(QA D1099: a midwife ended a doctor's examination)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the round-19 shape")
    void catchesTheOldShape() {
        String before = """
            class EncounterServiceImpl {
                public EncounterResponseDTO completeExamination(UUID encounterId) {
                    Encounter encounter = requireEncounterInScope(encounterId);
                    if (encounter.getStatus() != EncounterStatus.IN_PROGRESS) {
                        throw new BusinessException("encounter.exam.wrongStatus");
                    }
                    encounter.setStatus(EncounterStatus.READY_FOR_DISCHARGE);
                    return map(encounter);
                }
            }
            """;
        assertThat(offenders("EncounterServiceImpl", before)).containsExactly("EncounterServiceImpl#completeExamination");
        String after = before.replace("encounter.setStatus(",
            "EncounterClosureRules.requireExaminingClinician(encounter);\n        encounter.setStatus(");
        assertThat(offenders("EncounterServiceImpl", after)).isEmpty();
    }

    /** Methods that read IN_PROGRESS, write a status, name an examination-ending status and skip the rule. */
    static List<String> offenders(String cls, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (body.contains("EncounterStatus.IN_PROGRESS") && body.contains("setStatus(")
                    && ENDS_EXAM.matcher(body).find() && !RULE.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }
}
