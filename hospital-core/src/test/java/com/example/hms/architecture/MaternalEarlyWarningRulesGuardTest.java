package com.example.hms.architecture;

import com.example.hms.service.vitals.MaternalEarlyWarningRules;
import com.example.hms.service.vitals.MaternalEarlyWarningRules.Band;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy 2026-11-20 (PROVISIONAL, pending a named obstetric clinician's sign-off): the maternal early
 * warning has ONE rule set, {@code service/vitals/MaternalEarlyWarningRules}. Every surface that
 * scores, alerts, pages or sets a round asks it; none holds a threshold of its own, so the band the
 * clinician signs off (or changes) reaches the chart chip, the advisory card, the ward round, the
 * pages, the partograph and the postpartum observation at once. And wherever the score is shown, it
 * says « provisoire ».
 */
@DisplayName("Maternal early warning: one rule set, every surface, always « provisoire »")
class MaternalEarlyWarningRulesGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path I18N = Path.of("src/main/resources/i18n");
    private static final Path PORTAL = Path.of("../hospital-portal/src");

    private static String code(String relative) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
    }

    /** Each surface, and the call by which it asks the rule set (through the calculator or the service). */
    private static final Map<String, Pattern> SURFACES = Map.of(
        // every vitals write: nurse station, triage, the vitals API, and the rows a maternity screen charts
        "service/vitals/VitalSignJudgement.java", Pattern.compile("maternalEarlyWarning\\s*\\.\\s*assess\\("),
        // the chart chip
        "service/impl/PatientVitalSignServiceImpl.java", Pattern.compile("maternalEarlyWarning\\s*\\.\\s*scoreFor\\("),
        // the advisory card
        "cdshooks/bpa/NewsScoreProtocolRule.java", Pattern.compile("maternalEarlyWarning\\s*\\.\\s*scoreFor\\("),
        // the ward's vitals round
        "service/impl/NurseTaskServiceImpl.java", Pattern.compile("MaternalEarlyWarning\\s*\\.\\s*observationInterval\\("),
        // the partograph: WHO Labour Care Guide alert values and the shock index
        "service/impl/LaborServiceImpl.java",
        Pattern.compile("MaternalEarlyWarning\\s*\\.\\s*labourAlerts\\([\\s\\S]*MaternalEarlyWarning\\s*\\.\\s*shockAlert\\("),
        // the postpartum observation: the shock index
        "service/impl/PostpartumCareServiceImpl.java", Pattern.compile("MaternalEarlyWarning\\s*\\.\\s*shockAlert\\("));

    @Test
    void everySurfaceAsksTheOneRuleSet() throws IOException {
        for (Map.Entry<String, Pattern> surface : SURFACES.entrySet()) {
            assertThat(surface.getValue().matcher(code(surface.getKey())).find())
                .as("%s must read the maternal thresholds from MaternalEarlyWarningRules", surface.getKey())
                .isTrue();
        }
    }

    /**
     * The calculator and the service compare values with the rule set's getters only. The numbers they
     * may write are structural: 0, 1 and 2 (points, indexes), and the 10.0 that rounds a temperature
     * to the chart's one decimal.
     */
    @Test
    void theCalculatorAndTheServiceHoldNoThresholdOfTheirOwn() throws IOException {
        Pattern number = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?[LlFfDd]?(?![\\w.])");
        Pattern literal = Pattern.compile("\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\"");
        Set<String> allowed = Set.of("0", "1", "2", "10.0", "12");
        for (String file : List.of("service/vitals/MaternalEarlyWarning.java",
                "service/vitals/MaternalEarlyWarningService.java")) {
            Set<String> found = new TreeSet<>();
            Matcher m = number.matcher(literal.matcher(code(file)).replaceAll("\"\""));
            while (m.find()) {
                if (!allowed.contains(m.group())) {
                    found.add(m.group());
                }
            }
            assertThat(found).as("a number written in %s: a threshold belongs in MaternalEarlyWarningRules", file)
                .isEmpty();
        }
    }

    /** The labour thresholds the partograph once held itself are gone from it. */
    @Test
    void thePartographAndThePostpartumObservationDeclareNoMaternalVitalThreshold() throws IOException {
        Pattern constant = Pattern.compile(
            "(?i)\\b(?:MATERNAL_FEVER\\w*|MATERNAL_TACHYCARDIA\\w*|MATERNAL_BRADYCARDIA\\w*|MATERNAL_HYPOTENSION\\w*"
                + "|SHOCK_INDEX\\w*|HTN_SYSTOLIC|HTN_DIASTOLIC)\\s*=");
        assertThat(constant.matcher(code("service/impl/LaborServiceImpl.java")).find())
            .as("LaborServiceImpl declares a maternal vital threshold of its own").isFalse();
        assertThat(Pattern.compile("(?i)\\bSHOCK_INDEX\\w*\\s*=").matcher(code("service/impl/PostpartumCareServiceImpl.java")).find())
            .as("PostpartumCareServiceImpl declares a shock-index threshold of its own").isFalse();
    }

    /**
     * The defaults are the published values, pinned here from the sources so an edit to a default is a
     * deliberate, reviewed change (with the clinician's sign-off), never a slip.
     */
    @Test
    void theDefaultsAreThePublishedValues() {
        MaternalEarlyWarningRules rules = new MaternalEarlyWarningRules();
        MaternalEarlyWarningRules.Mews mews = rules.getMews();
        // NHS England national MEWS chart (2024).
        assertBand(mews.getRespiratoryRate(), 6.0, 8.0, 22.0, 25.0);
        assertBand(mews.getSpo2(), 92.0, 94.0, null, null);
        assertBand(mews.getTemperature(), 35.6, 36.1, 37.3, 37.5);
        assertBand(mews.getPulse(), 62.0, 70.0, 113.0, 122.0);
        assertBand(mews.getPulsePostBirth(), 50.0, 57.0, 99.0, 108.0);
        assertBand(mews.getSystolic(), 93.0, 100.0, 136.0, 145.0);
        assertBand(mews.getDiastolic(), 56.0, 61.0, 89.0, 97.0);
        assertThat(mews.getPostBirthPulseFromHours()).isEqualTo(48);
        assertThat(List.of(mews.getLowMediumFrom(), mews.getMediumFrom(), mews.getHighFrom())).containsExactly(2, 5, 8);
        assertThat(List.of(mews.getLowMediumReviewWithinMinutes(), mews.getMediumReviewWithinMinutes(),
            mews.getHighReviewWithinMinutes())).containsExactly(30, 15, 0);
        assertThat(List.of(mews.getLowMediumObservationMinutes(), mews.getMediumObservationMinutes(),
            mews.getHighObservationMinutes(), mews.getConcernObservationMinutes())).containsExactly(30, 15, 15, 30);
        assertThat(mews.getConcernRaisesTierAbove()).isEqualTo(2);
        // CRADLE Vital Signs Alert (Vousden 2018).
        assertThat(rules.getCradle().getShockIndexYellowFrom()).isEqualTo(0.9);
        assertThat(rules.getCradle().getShockIndexRedFrom()).isEqualTo(1.7);
        // WHO Labour Care Guide (2020) alert values.
        MaternalEarlyWarningRules.Labour labour = rules.getLabour();
        assertThat(List.of(labour.getPulseLowBelow(), labour.getPulseHighFrom(), labour.getSystolicLowBelow(),
            labour.getSystolicHighFrom(), labour.getDiastolicHighFrom())).containsExactly(60, 120, 80, 140, 90);
        assertThat(labour.getTemperatureLowBelow()).isEqualTo(35.0);
        assertThat(labour.getTemperatureHighFrom()).isEqualTo(37.5);
        // The local mapping of the chart's UK grades (provisional).
        assertThat(rules.getEscalation().getMidwifeInChargeRoles()).containsExactly("ROLE_MIDWIFE");
        assertThat(rules.getEscalation().getDoctorRoles()).containsExactly("ROLE_DOCTOR");
        assertThat(rules.getEscalation().getSeniorDoctorRoles()).containsExactly("ROLE_DOCTOR");
        assertThat(rules.isEnabled()).isTrue();
        // Review of #1241: what applies when the rule set is off (the partograph before the policy), and the
        // page cool-down (decision 2026-11-20, provisional).
        MaternalEarlyWarningRules.LabourBeforePolicy before = rules.getLabourBeforePolicy();
        assertThat(before.getTemperatureHighFrom()).isEqualTo(38.0);
        assertThat(List.of(before.getPulseHighAbove(), before.getSystolicHighFrom(), before.getDiastolicHighFrom()))
            .containsExactly(120, 140, 90);
        assertThat(rules.getEscalation().getPageCoolDownMinutes()).isEqualTo(30);
    }

    /**
     * Review of #1241: switched off, every surface is as it was before the policy. The three that kept
     * their new behaviour when the switch was first added (the partograph's alerts, the shock-index alert,
     * the obstetric scope asked before the age) each ask the switch; the tests of each prove the old values.
     */
    @Test
    void switchedOffEverySurfaceAsksTheSwitch() throws IOException {
        String calculator = code("service/vitals/MaternalEarlyWarning.java");
        assertThat(Pattern.compile("shockAlert\\([^)]*\\)\\s*\\{\\s*if \\(!rules\\.isEnabled\\(\\)\\)").matcher(calculator).find())
            .as("shockAlert returns none when the rule set is off").isTrue();
        assertThat(Pattern.compile("labourAlerts\\([^)]*\\)\\s*\\{\\s*if \\(!rules\\.isEnabled\\(\\)\\)").matcher(calculator).find())
            .as("labourAlerts answers with the earlier alerts when the rule set is off").isTrue();
        assertThat(code("service/vitals/News2Applicability.java")).contains("!maternalRules.isEnabled()");
        assertThat(code("service/vitals/MaternalEarlyWarningService.java")).contains("!rules.isEnabled()");
        // The maternity services hold no branch of their own: they ask the calculator, which asks the switch.
        assertThat(code("service/impl/LaborServiceImpl.java")).doesNotContain("isEnabled()");
        assertThat(code("service/impl/PostpartumCareServiceImpl.java")).doesNotContain("maternalRules.isEnabled()");
    }

    private static void assertBand(Band band, Double twoAtOrBelow, Double oneAtOrBelow, Double oneFrom, Double twoFrom) {
        assertThat(band.getTwoAtOrBelow()).isEqualTo(twoAtOrBelow);
        assertThat(band.getOneAtOrBelow()).isEqualTo(oneAtOrBelow);
        assertThat(band.getOneFrom()).isEqualTo(oneFrom);
        assertThat(band.getTwoFrom()).isEqualTo(twoFrom);
    }

    /** The rule set cites the source of each family of thresholds, and says it is provisional. */
    @Test
    void theRuleSetCitesItsSourcesAndSaysProvisional() throws IOException {
        String source = Files.readString(MAIN.resolve("service/vitals/MaternalEarlyWarningRules.java"),
            StandardCharsets.UTF_8);
        assertThat(source)
            .contains("PROVISIONAL")
            .contains("National MEWS")
            .contains("doi:10.1136/bmjmed-2023-000748")
            .contains("doi:10.1186/s12884-018-1737-x")
            .contains("WHO Labour Care Guide")
            .contains("NEWS2 final report");
    }

    /** Every reader of the score is told it is provisional: the pages, the card, the labour alerts and the chart. */
    @Test
    void everySurfaceThatShowsTheScoreSaysProvisoire() throws IOException {
        Map<String, String> clinical = properties("messages-clinical_fr.properties");
        Map<String, String> maternity = properties("messages-maternity_fr.properties");
        Set<String> silent = new TreeSet<>();
        clinical.forEach((key, value) -> {
            if ((key.startsWith("mews.escalation.") || key.equals("cds.bpa.mews.title")
                    || key.equals("cds.bpa.mews.provisional") || key.endsWith(".notification.pregnancy.mews"))
                    && !value.toLowerCase().contains("provisoire")) {
                silent.add(key);
            }
        });
        maternity.forEach((key, value) -> {
            if ((key.startsWith("labor.alert.msg.labor-maternal-hypo") || key.startsWith("labor.alert.msg.labor-maternal-brady")
                    || key.contains("-shock")) && key.contains(".msg.") && !value.toLowerCase().contains("provisoire")) {
                silent.add(key);
            }
            if (key.endsWith(".notification.mews") && !value.toLowerCase().contains("provisoire")) {
                silent.add(key);
            }
        });
        assertThat(silent).as("a French text of the maternal early warning that does not say « provisoire »").isEmpty();
        assertThat(clinical).containsKeys("mews.escalation.lowMedium", "mews.escalation.medium", "mews.escalation.high",
            "mews.escalation.red", "cds.bpa.mews.provisional");
        assertThat(code("cdshooks/bpa/NewsScoreProtocolRule.java")).contains("\"cds.bpa.mews.provisional\"");

        // The portal: the chip, the tick list and the toast each carry a text that says « provisoire ».
        String fr = Files.readString(PORTAL.resolve("assets/i18n/fr.json"), StandardCharsets.UTF_8);
        for (String key : List.of("MEWS_PROVISIONAL", "MATERNAL_CONCERNS", "MATERNAL_EWS_WARNING")) {
            Matcher m = Pattern.compile("\"" + key + "[A-Z_]*\"\\s*:\\s*(?:\\{[^}]*\\}|\"[^\"]*\")").matcher(fr);
            assertThat(m.find()).as("fr.json has %s", key).isTrue();
            assertThat(m.group().toLowerCase()).as("%s says « provisoire »", key).contains("provisoire");
        }
        String chart = Files.readString(PORTAL.resolve("app/patients/patient-detail.html"), StandardCharsets.UTF_8);
        assertThat(chart).contains("mews-chip").contains("MEWS_PROVISIONAL");
    }

    /**
     * A family proxy is not shown what reveals a pregnancy: her read maps the row with the plain mapper,
     * which never carries the ticked maternal concerns or the maternal score (the staff chart and the
     * patient's own portal read them through the responder).
     */
    @Test
    void aFamilyProxyIsNotShownTheMaternalConcernsOrTheScore() throws IOException {
        assertThat(code("mapper/PatientVitalSignMapper.java"))
            .doesNotContain(".maternalConcerns(")
            .doesNotContain("mews");
        String service = code("service/impl/PatientVitalSignServiceImpl.java");
        int proxy = service.indexOf("getRecentVitalsForProxy(");
        assertThat(proxy).isPositive();
        String body = service.substring(proxy, SourceScan.closingBrace(service, service.indexOf('{', proxy)));
        assertThat(body).contains("vitalSignMapper::toResponse").doesNotContain("responder(");
    }

    /**
     * Review of #1241, round 2: the cool-down is asked in ONE place, and never for a RED add-on alert. A
     * high page of ten minutes ago silenced a severe shock on the vitals paths, where nothing else pages.
     * A second call site, or the one call without the red exemption, fails here.
     */
    @Test
    void theCoolDownNeverWithholdsARedAlert() throws IOException {
        String service = code("service/vitals/MaternalEarlyWarningService.java");
        Matcher calls = Pattern.compile("pagedRecently\\(").matcher(service);
        int occurrences = 0;
        while (calls.find()) {
            occurrences++;
        }
        assertThat(occurrences).as("pagedRecently: its declaration and exactly one call").isEqualTo(2);
        assertThat(Pattern.compile("!\\s*score\\.red\\(\\)\\s*&&\\s*pagedRecently\\(").matcher(service).find())
            .as("the cool-down call is guarded by !score.red(): a RED alert is never withheld").isTrue();
        // The three write paths hand the page's outcome to the recorder's warning: it never says « prévenue »
        // for a page that was withheld.
        for (String writer : List.of("service/impl/PatientVitalSignServiceImpl.java",
                "service/impl/NurseTaskServiceImpl.java", "service/EncounterServiceImpl.java")) {
            assertThat(code(writer)).as(writer).contains("maternalAlerts(judgement, maternalPage)")
                .doesNotContain("maternalAlerts(judgement)");
        }
        String portal = Files.readString(PORTAL.resolve("app/shared/newborn-vital-alerts.ts"), StandardCharsets.UTF_8);
        assertThat(portal).as("the portal says « prévenue » only for a plain mews-<level> code")
            .contains("MEWS_PAGED_CODE");
    }

    /** The portal draws what the server computed: it never scores a vital sign or holds a band itself. */
    @Test
    void thePortalHoldsNoMaternalThresholdOfItsOwn() throws IOException {
        Pattern ownScore = Pattern.compile("(?i)(shockIndex|shock_index|mewsPoints|mewsBand)\\s*[=:(]");
        Set<String> copies = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(PORTAL.resolve("app"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts")).toList()) {
                if (ownScore.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    copies.add(PORTAL.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(copies).as("a portal source that computes a maternal score or shock index itself").isEmpty();
    }

    private static Map<String, String> properties(String name) throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(I18N.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> map = new java.util.TreeMap<>();
        properties.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return map;
    }
}
