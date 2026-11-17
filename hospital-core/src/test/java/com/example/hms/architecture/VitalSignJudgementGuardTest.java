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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1564 guard (round 43, class: a vitals path that judges a newborn with adult limits, or
 * not at all). The nurse station flagged a 4-day-old's HR 80, 35.0 °C and 37.8 °C as not
 * significant; triage judged nothing; only maternity's newborn assessment knew the newborn bands.
 *
 * <p>Every production method that saves a {@code PatientVitalSign} through a
 * {@code PatientVitalSignRepository} must judge it with {@code vitalSignJudgement.judge(} and
 * escalate it with {@code vitalSignJudgement.escalate(} in the same method. Nothing but the
 * judgement calls the NEWS2 escalation directly, and the judgement reads the newborn bands from
 * {@code NewbornAlertRules} (never a copy) and never refuses a reading. No allowlist.
 */
@DisplayName("Every vitals write goes through the one judgement, newborns included")
class VitalSignJudgementGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path JUDGEMENT = MAIN.resolve("service/vitals/VitalSignJudgement.java");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected|static)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern REPO_FIELD = Pattern.compile("PatientVitalSignRepository\\s++(\\w++)\\s*+[;=,)]");
    private static final Pattern JUDGE = Pattern.compile("\\bvitalSignJudgement\\.judge\\(");
    private static final Pattern ESCALATE = Pattern.compile("\\bvitalSignJudgement\\.escalate\\(");

    @Test
    @DisplayName("every method that saves a vital sign judges and escalates it")
    void everyVitalsWriteIsJudged() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        Set<String> directNews2 = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String cls = file.getFileName().toString().replace(".java", "");
                writers.addAll(writers(cls, source));
                offenders.addAll(offenders(cls, source));
                if (source.contains(".escalateIfNeeded(") && !rel.equals("service/vitals/VitalSignJudgement.java")) {
                    directNews2.add(rel);
                }
            }
        }
        assertThat(writers).as("the vitals write paths found (recordVital, triage, nurse station)")
            .contains("PatientVitalSignServiceImpl#recordVital", "EncounterServiceImpl#submitTriage",
                "NurseTaskServiceImpl#captureVitals");
        assertThat(offenders)
            .as("a method saves a vital sign without the shared judgement (QA D1564: a newborn's vitals were "
                + "judged with adult limits): call vitalSignJudgement.judge(vital) before the save and "
                + "vitalSignJudgement.escalate(...) after it" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(directNews2)
            .as("only VitalSignJudgement escalates a vital set; a path calling NEWS2 itself skips the newborn notices"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the judgement reuses the newborn rules and never refuses a reading")
    void theJudgementReusesTheNewbornRulesAndNeverRefuses() throws IOException {
        String code = SourceScan.stripComments(Files.readString(JUDGEMENT, StandardCharsets.UTF_8));
        assertThat(code).contains("NewbornAlertRules.evaluate(reading)")
            .contains("NewbornAlertRules.hoursSinceBirth(")
            .contains("NewbornAlertRules.PRETERM_BELOW_WEEKS")
            .doesNotContain("throw ")
            // The bands live in NewbornAlertRules only: no copy of a newborn threshold here.
            .doesNotContainPattern("\\b(?:100|160|170|25|60)\\b")
            .doesNotContainPattern("\\b3[6-8]\\.[05]\\b");
        // The provisional status stays written down beside the judgement.
        assertThat(Files.readString(JUDGEMENT, StandardCharsets.UTF_8))
            .contains("PROVISIONAL").contains("neonatal lead's approval");
    }

    @Test
    @DisplayName("the scan catches the round-43 shape")
    void catchesTheOldShape() {
        String before = """
            class NurseTaskServiceImpl {
                private final PatientVitalSignRepository vitalSignRepository;

                public void captureVitals(UUID patientId, NurseVitalCaptureRequestDTO request) {
                    PatientVitalSign vital = PatientVitalSign.builder().heartRateBpm(request.getHeartRateBpm()).build();
                    vital.setClinicallySignificant(isClinicallySignificant(request));
                    vitalSignRepository.save(vital);
                    news2EscalationService.escalateIfNeeded(vital, patientId, hospitalId, nurse);
                }
            }
            """;
        assertThat(offenders("NurseTaskServiceImpl", before)).containsExactly("NurseTaskServiceImpl#captureVitals");
        String judgedOnly = before.replace("vital.setClinicallySignificant(isClinicallySignificant(request));",
            "Judgement j = vitalSignJudgement.judge(vital);");
        assertThat(offenders("NurseTaskServiceImpl", judgedOnly)).as("judged but not escalated")
            .containsExactly("NurseTaskServiceImpl#captureVitals");
        String after = judgedOnly.replace("news2EscalationService.escalateIfNeeded(vital, patientId, hospitalId, nurse);",
            "vitalSignJudgement.escalate(vital, j, patientId, hospitalId, nurse, null);");
        assertThat(offenders("NurseTaskServiceImpl", after)).isEmpty();
    }

    static List<String> writers(String cls, String source) {
        return scan(cls, source, false);
    }

    static List<String> offenders(String cls, String source) {
        return scan(cls, source, true);
    }

    private static List<String> scan(String cls, String source, boolean offendersOnly) {
        List<String> out = new ArrayList<>();
        Pattern save = savePattern(source);
        if (save == null) {
            return out;
        }
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (!save.matcher(body).find()) {
                continue;
            }
            if (!offendersOnly || !JUDGE.matcher(body).find() || !ESCALATE.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }

    /** A save through a PatientVitalSignRepository field of this source, or null when it has none. */
    private static Pattern savePattern(String source) {
        List<String> alternatives = new ArrayList<>();
        Matcher repo = REPO_FIELD.matcher(source);
        while (repo.find()) {
            alternatives.add("\\b" + repo.group(1) + "\\.save(?:All|AndFlush)?+\\(");
        }
        return alternatives.isEmpty() ? null : Pattern.compile(String.join("|", alternatives));
    }
}
