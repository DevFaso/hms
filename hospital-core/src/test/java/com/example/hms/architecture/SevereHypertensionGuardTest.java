package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1890 (2026-11-14) and r54 (2026-11-15), both provisional: severe blood pressure raised nothing
 * after a birth (D1890), and nothing at all for an adult with no delivery (r54: 164/114 and higher
 * saved as not significant). The rule now lives in one place, {@code service/vitals/SevereHypertension}:
 * obstetric (pregnancy, 42 days after a birth) at 160/110, adult at 180/120. Every surface asks it,
 * and no source (backend or portal) compares a blood pressure with those numbers on its own, so a
 * change of threshold or of scope (the obstetric and clinical leads may change them) reaches every
 * surface at once.
 */
@DisplayName("Severe blood pressure: one rule, every surface")
class SevereHypertensionGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PORTAL = Path.of("../hospital-portal/src/app");
    private static final String EVALUATOR = "service/vitals/SevereHypertension.java";

    /** Each surface, and the call by which it asks the shared rule. */
    private static final Map<String, Pattern> SURFACES = Map.of(
        // the midwife's postpartum observation
        "service/impl/PostpartumCareServiceImpl.java",
        Pattern.compile("severeHypertension\\s*\\.\\s*evaluate\\(|SevereHypertension\\s*\\.\\s*judge\\("),
        // nurse station, triage and the vitals API
        "service/vitals/VitalSignJudgement.java", Pattern.compile("severeHypertension\\s*\\.\\s*evaluate\\("),
        // the doctor's BPA panel: after birth, and the adult card
        "cdshooks/bpa/PostpartumSevereHypertensionRule.java", Pattern.compile("evaluator\\s*\\.\\s*evaluate\\("),
        "cdshooks/bpa/AdultSevereHypertensionRule.java", Pattern.compile("evaluator\\s*\\.\\s*evaluate\\("),
        // the pregnancy advisory and the partograph's severe range
        "cdshooks/bpa/PregnancyHypertensionProtocolRule.java",
        Pattern.compile("SevereHypertension\\s*\\.\\s*isObstetricSevere\\("),
        "service/impl/LaborServiceImpl.java", Pattern.compile("SevereHypertension\\s*\\.\\s*isObstetricSevere\\("));

    private static final String PRESSURE = "(?i)(?:systolic|diastolic|\\bsys\\b|\\bdia\\b|\\bsbp\\b|\\bdbp\\b)[\\w.()?!]*+\\s*+";
    /**
     * A pressure compared with a severe number written out, either side of either threshold. A strict
     * {@code > 120} is not the adult rule (it is the nurse station's older per-vital ceiling, D1564),
     * so among strict comparisons only {@code >} 160/110 counts as a copy.
     */
    private static final Pattern LITERAL_SEVERE_BP = Pattern.compile(PRESSURE
        + "(?:(?:>=|<)\\s*(?:160|110|180|120)\\b|>\\s*(?:160|110)\\b|(?:>|<=)\\s*(?:159|109|179|119)\\b)");
    /** A constant set to a severe number written out; {@link #isPressureName} decides if it is a pressure. */
    private static final Pattern LITERAL_SEVERE_CONSTANT =
        Pattern.compile("\\b(\\w++)\\s*+=\\s*+(?:160|110|180|120)\\s*+;");
    private static final Pattern PRESSURE_NAME = Pattern.compile("(?i)systolic|diastolic|sbp|dbp|htn");

    private static boolean isPressureName(String name) {
        return PRESSURE_NAME.matcher(name).find();
    }

    private static boolean declaresASevereConstant(String source) {
        java.util.regex.Matcher m = LITERAL_SEVERE_CONSTANT.matcher(source);
        while (m.find()) {
            if (isPressureName(m.group(1))) return true;
        }
        return false;
    }

    // Unrolled loops (a group repeats only per escape): a long template literal must not overflow the
    // regex stack.
    private static final Pattern STRING_LITERAL =
        Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\"");
    private static final Pattern TS_STRING_LITERAL = Pattern.compile("`[^`\\\\]*+(?:\\\\.[^`\\\\]*+)*+`"
        + "|'[^'\\\\\\n]*+(?:\\\\.[^'\\\\\\n]*+)*+'|\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\"");

    private static String code(String relative) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
    }

    @Test
    void everySurfaceAsksTheSharedRule() throws IOException {
        for (Map.Entry<String, Pattern> surface : SURFACES.entrySet()) {
            assertThat(surface.getValue().matcher(code(surface.getKey())).find())
                .as("%s must judge the pressure with SevereHypertension", surface.getKey())
                .isTrue();
        }
    }

    @Test
    void theSurfacesUseTheEvaluatorThatSpringInjects() throws IOException {
        assertThat(code("cdshooks/bpa/PostpartumSevereHypertensionRule.java"))
            .contains("private final SevereHypertension evaluator;");
        assertThat(code("cdshooks/bpa/AdultSevereHypertensionRule.java"))
            .contains("private final SevereHypertension evaluator;");
        assertThat(code("service/vitals/VitalSignJudgement.java"))
            .contains("private final SevereHypertension severeHypertension;");
        assertThat(code("service/impl/PostpartumCareServiceImpl.java"))
            .contains("service.vitals.SevereHypertension severeHypertension;");
    }

    /**
     * r54 review P1: the rule read pregnancy, labour and birth records at every hospital, past a
     * patient's sharing opt-out. Every obstetric read goes through the record-access policy's readable
     * hospitals; no unscoped finder is called, and a foreign record behind a warning is audited.
     */
    @Test
    void theRuleReadsObstetricRecordsOnlyThroughTheRecordAccessPolicy() throws IOException {
        String evaluator = code(EVALUATOR);
        assertThat(evaluator).contains("recordAccessPolicy.readableHospitalIds(").contains("reachRecorder.recordReach(");
        assertThat(evaluator)
            .doesNotContain("findCurrentByPatientId(")
            .doesNotContain("existsByPatient_IdAndStatus(")
            .doesNotContain("findFirstByPatient_IdAndStatusOrderByAdmittedAtDesc(")
            .doesNotContain("existsByPatient_IdAndBirthDateTime");
        java.util.regex.Matcher finder = Pattern.compile("Repository\\s*+\\.\\s*+(\\w++)\\(").matcher(evaluator);
        int finders = 0;
        while (finder.find()) {
            finders++;
            assertThat(finder.group(1)).as("an obstetric read scoped to the readable hospitals").contains("Hospital_IdIn");
        }
        assertThat(finders).as("the three obstetric finders were found").isGreaterThanOrEqualTo(3);
    }

    @Test
    void noBackendSourceComparesABloodPressureWithTheSevereNumbersItself() throws IOException {
        Set<String> copies = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (relative.equals(EVALUATOR)) continue;
                // Card and message text may quote the rule; only code compares.
                String withoutText = STRING_LITERAL.matcher(code(relative)).replaceAll("\"\"");
                if (LITERAL_SEVERE_BP.matcher(withoutText).find() || declaresASevereConstant(withoutText)) {
                    copies.add(relative);
                }
            }
        }
        assertThat(copies).as("a second copy of the severe thresholds").isEmpty();
    }

    @Test
    void thePortalHoldsNoSevereThresholdOfItsOwn() throws IOException {
        assertThat(PORTAL).isDirectory();
        Set<String> copies = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(PORTAL)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String withoutText = TS_STRING_LITERAL.matcher(source).replaceAll("''");
                if (LITERAL_SEVERE_BP.matcher(withoutText).find()) {
                    copies.add(PORTAL.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(copies).as("the portal judges no severe pressure: the server's warning is the rule").isEmpty();
    }
}
