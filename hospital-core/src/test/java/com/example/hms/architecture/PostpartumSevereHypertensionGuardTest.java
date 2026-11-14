package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1890 (provisional, 2026-11-14): severe blood pressure after birth raised nothing on any of
 * the three surfaces a midwife, a nurse or a doctor reads. The rule now lives in one place,
 * {@code PostpartumSevereHypertension}; the postpartum observation, the shared vitals judgement
 * (every vitals write) and the BPA panel each ask it, and no source compares a blood pressure with
 * the severe numbers on its own, so a change of threshold (the obstetric lead may change them)
 * reaches all three at once.
 */
@DisplayName("Severe blood pressure after birth: one rule, three surfaces")
class PostpartumSevereHypertensionGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String EVALUATOR = "service/maternity/PostpartumSevereHypertension.java";
    private static final Pattern ASKS_THE_RULE =
        Pattern.compile("PostpartumSevereHypertension\\s*\\.\\s*isSevere\\(|postpartumSevereHypertension\\s*\\.\\s*applies\\("
            + "|evaluator\\s*\\.\\s*applies\\(");
    /** A blood pressure compared with a severe number written out (160/110, or 159/109 with a >). */
    private static final Pattern LITERAL_SEVERE_BP = Pattern.compile(
        "(?i)(?:systolic|diastolic|\\bsys\\b|\\bdia\\b)[\\w.()]*\\s*(?:>=|>)\\s*(?:160|110|159|109)\\b");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"");

    private static String code(String relative) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
    }

    @Test
    void theThreeSurfacesAskTheSharedRule() throws IOException {
        for (String surface : List.of(
                "service/impl/PostpartumCareServiceImpl.java",   // the midwife's postpartum observation
                "service/vitals/VitalSignJudgement.java",        // nurse station, triage and the vitals API
                "cdshooks/bpa/PostpartumSevereHypertensionRule.java")) { // the doctor's BPA panel
            assertThat(ASKS_THE_RULE.matcher(code(surface)).find())
                .as("%s must judge the pressure with PostpartumSevereHypertension", surface)
                .isTrue();
        }
    }

    @Test
    void theRuleUsesTheEvaluatorThatSpringInjects() throws IOException {
        assertThat(code("cdshooks/bpa/PostpartumSevereHypertensionRule.java"))
            .contains("private final PostpartumSevereHypertension evaluator;");
        assertThat(code("service/vitals/VitalSignJudgement.java"))
            .contains("service.maternity.PostpartumSevereHypertension postpartumSevereHypertension;");
    }

    @Test
    void noSourceComparesABloodPressureWithTheSevereNumbersItself() throws IOException {
        Set<String> copies = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (relative.equals(EVALUATOR)) continue;
                // Card and message text may quote the rule; only code compares.
                String withoutText = STRING_LITERAL.matcher(code(relative)).replaceAll("\"\"");
                if (LITERAL_SEVERE_BP.matcher(withoutText).find()) {
                    copies.add(relative);
                }
            }
        }
        assertThat(copies).as("a second copy of the severe thresholds").isEmpty();
    }
}
