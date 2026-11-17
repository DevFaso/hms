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
 * QA D1077 guard (round 19, NOT_FIXED twice): a vitals write path that stores
 * a measure the patient cannot have. Round 18 put the age rule on the nurse
 * station and the sex rule on triage, and said "the server refuses them too";
 * the generic {@code POST /patients/{id}/vitals} still stored a head
 * circumference of 56 cm for a 67-year-old.
 *
 * <p>Every production method that writes a {@code PatientVitalSign} (saves it
 * through a {@code PatientVitalSignRepository}, applies a request onto it with
 * {@code PatientVitalSignMapper.applyRequestToEntity}, or builds one with a
 * head circumference) must call a {@code PatientMeasureRules.require…} rule in
 * the same method. Every class that reads a fundal height from a request must
 * check it ({@code requireVitalSignApplicable}, {@code requireFundalHeightApplicable},
 * or the obstetric records' {@code requireObstetricEligible}). No allowlist.
 */
@DisplayName("Every vitals write goes through the measure rules")
class VitalSignMeasureRuleGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected|static)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern REPO_FIELD = Pattern.compile("PatientVitalSignRepository\\s++(\\w++)\\s*+[;=,)]");
    private static final Pattern MAPPER_FIELD = Pattern.compile("PatientVitalSignMapper\\s++(\\w++)\\s*+[;=,)]");
    private static final Pattern RULE = Pattern.compile("PatientMeasureRules\\.require\\w*\\(");
    private static final Pattern FUNDAL_READ = Pattern.compile("\\b(?:req|request)\\w*+\\.getFund(?:al|us)HeightCm\\(\\)");
    private static final Pattern FUNDAL_RULE = Pattern.compile(
        "requireVitalSignApplicable\\(|requireFundalHeightApplicable\\(|requireObstetricEligible\\(");

    @Test
    @DisplayName("every method that writes a vital sign calls the measure rule")
    void everyVitalsWriteIsChecked() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        List<String> fundalOffenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("payload/")) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String cls = file.getFileName().toString().replace(".java", "");
                for (String site : writes(cls, source)) {
                    writers.add(site);
                }
                offenders.addAll(offenders(cls, source));
                if (FUNDAL_READ.matcher(source).find() && !FUNDAL_RULE.matcher(source).find()) {
                    fundalOffenders.add(rel);
                }
            }
        }
        assertThat(writers).as("the vitals write paths found (recordVital, triage, nurse station)")
            .contains("PatientVitalSignServiceImpl#recordVital", "EncounterServiceImpl#submitTriage",
                "NurseTaskServiceImpl#captureVitals");
        assertThat(offenders)
            .as("a method writes a vital sign without PatientMeasureRules (QA D1077: the generic vitals endpoint "
                + "stored a head circumference for an adult): call "
                + "PatientMeasureRules.requireVitalSignApplicable(vital, fundalHeightCm, today) before the save"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(fundalOffenders)
            .as("a class reads a fundal height from a request and never checks it against the patient's sex (QA D1077)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the round-19 shape")
    void catchesTheOldShape() {
        String before = """
            class PatientVitalSignServiceImpl {
                private final PatientVitalSignRepository vitalSignRepository;
                private final PatientVitalSignMapper vitalSignMapper;

                public PatientVitalSignResponseDTO recordVital(UUID patientId, PatientVitalSignRequestDTO request) {
                    PatientVitalSign entity = PatientVitalSign.builder().patient(patient).build();
                    vitalSignMapper.applyRequestToEntity(request, entity);
                    PatientVitalSign saved = vitalSignRepository.save(entity);
                    return map(saved);
                }
            }
            """;
        assertThat(offenders("PatientVitalSignServiceImpl", before))
            .containsExactly("PatientVitalSignServiceImpl#recordVital");
        String after = before.replace("PatientVitalSign saved =",
            "PatientMeasureRules.requireVitalSignApplicable(entity, null, today);\n        PatientVitalSign saved =");
        assertThat(offenders("PatientVitalSignServiceImpl", after)).isEmpty();

        String builder = """
            class X {
                public void capture(Request request) {
                    PatientVitalSign v = PatientVitalSign.builder().headCircumferenceCm(request.getHead()).build();
                    repo.persist(v);
                }
            }
            """;
        assertThat(offenders("X", builder)).as("a builder that writes a head circumference").containsExactly("X#capture");
    }

    /** The methods of {@code source} that write a vital sign. */
    static List<String> writes(String cls, String source) {
        List<String> out = new ArrayList<>();
        Pattern write = writePattern(source);
        if (write == null) {
            return out;
        }
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (write.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }

    static List<String> offenders(String cls, String source) {
        List<String> out = new ArrayList<>();
        Pattern write = writePattern(source);
        if (write == null || "PatientVitalSignMapper".equals(cls)) {
            // The mapper's applyRequestToEntity is checked at its callers.
            return out;
        }
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (write.matcher(body).find() && !RULE.matcher(body).find()) {
                out.add(cls + "#" + CancelledOrderLockGuardTest.methodName(source.substring(m.start(), m.start(1))));
            }
        }
        return out;
    }

    /** What counts as a vitals write in this source, or null when it cannot write one. */
    private static Pattern writePattern(String source) {
        List<String> alternatives = new ArrayList<>();
        Matcher repo = REPO_FIELD.matcher(source);
        while (repo.find()) {
            alternatives.add("\\b" + repo.group(1) + "\\.save(?:All|AndFlush)?+\\(");
        }
        Matcher mapper = MAPPER_FIELD.matcher(source);
        while (mapper.find()) {
            alternatives.add("\\b" + mapper.group(1) + "\\.applyRequestToEntity\\(");
        }
        if (source.contains("PatientVitalSign.builder()")) {
            alternatives.add("PatientVitalSign\\.builder\\(\\)[^;]*?\\.headCircumferenceCm\\(");
        }
        alternatives.add("\\bsetHeadCircumferenceCm\\(");
        if (!source.contains("PatientVitalSign")) {
            return null;
        }
        return Pattern.compile(String.join("|", alternatives));
    }
}
