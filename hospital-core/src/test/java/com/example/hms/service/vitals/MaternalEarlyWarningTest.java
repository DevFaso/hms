package com.example.hms.service.vitals;

import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.enums.MaternalConcern;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.service.vitals.MaternalEarlyWarning.HardAlert;
import com.example.hms.service.vitals.MaternalEarlyWarning.LabourAlert;
import com.example.hms.service.vitals.MaternalEarlyWarning.Parameter;
import com.example.hms.service.vitals.MaternalEarlyWarning.Score;
import com.example.hms.service.vitals.MaternalEarlyWarning.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy 2026-11-20 (PROVISIONAL, pending a named obstetric clinician's sign-off): every band edge of
 * the NHS England national MEWS chart (2024), the 48 h pulse switch, the additional concerns, the
 * four levels, the CRADLE shock index and the WHO Labour Care Guide alert values. The expected numbers
 * are written out here from the sources, never read from the rule set under test.
 */
class MaternalEarlyWarningTest {

    private static final MaternalEarlyWarningRules RULES = new MaternalEarlyWarningRules();
    private static final Duration ROUTINE = Duration.ofHours(2);

    /** A set every vital sign of which scores 0 (antenatal pulse table and post-birth table alike). */
    private static PatientVitalSign.PatientVitalSignBuilder normal() {
        return PatientVitalSign.builder().respiratoryRateBpm(16).spo2Percent(98).temperatureCelsius(36.8)
            .heartRateBpm(80).systolicBpMmHg(118).diastolicBpMmHg(74);
    }

    private static Score score(PatientVitalSign vital) {
        return MaternalEarlyWarning.score(RULES, vital, false);
    }

    @ParameterizedTest(name = "respiratory rate {0} scores {1}")
    @CsvSource({"6,2", "7,1", "8,1", "9,0", "21,0", "22,1", "24,1", "25,2"})
    void respiratoryRateBands(int value, int points) {
        Score s = score(normal().respiratoryRateBpm(value).build());
        assertThat(s.points()).containsEntry(Parameter.RESPIRATORY_RATE, points);
        assertThat(s.total()).isEqualTo(points);
    }

    @ParameterizedTest(name = "SpO2 {0} scores {1}")
    @CsvSource({"92,2", "93,1", "94,1", "95,0", "100,0"})
    void oxygenSaturationBands(int value, int points) {
        assertThat(score(normal().spo2Percent(value).build()).points()).containsEntry(Parameter.SPO2, points);
    }

    @ParameterizedTest(name = "temperature {0} scores {1}")
    @CsvSource({"35.6,2", "35.7,1", "36.1,1", "36.2,0", "37.2,0", "37.3,1", "37.4,1", "37.5,2", "39.0,2"})
    void temperatureBandsAreTheOfficialCharts(double value, int points) {
        assertThat(score(normal().temperatureCelsius(value).build()).points())
            .containsEntry(Parameter.TEMPERATURE, points);
    }

    @Test
    @DisplayName("the chart's temperature bands, not the derivation paper's (35.6 scores 2, 37.5 scores 2)")
    void theChartNotThePaper() {
        // Gerry 2024, Table 2, would score 35.6 as 0 (35.6–37.2) and 37.5 as 1 (37.3–37.5).
        assertThat(score(normal().temperatureCelsius(35.6).build()).total()).isEqualTo(2);
        assertThat(score(normal().temperatureCelsius(37.5).build()).total()).isEqualTo(2);
    }

    @ParameterizedTest(name = "pulse {0} before 48 h scores {1}")
    @CsvSource({"62,2", "63,1", "70,1", "71,0", "112,0", "113,1", "121,1", "122,2"})
    void pulseBandsAntenatalAndUpTo48Hours(int value, int points) {
        assertThat(MaternalEarlyWarning.score(RULES, normal().heartRateBpm(value).build(), false).points())
            .containsEntry(Parameter.PULSE, points);
    }

    @ParameterizedTest(name = "pulse {0} from 48 h after the birth scores {1}")
    @CsvSource({"50,2", "51,1", "57,1", "58,0", "98,0", "99,1", "107,1", "108,2"})
    void pulseBandsFrom48HoursAfterTheBirth(int value, int points) {
        Score s = MaternalEarlyWarning.score(RULES, normal().heartRateBpm(value).build(), true);
        assertThat(s.points()).containsEntry(Parameter.PULSE, points);
        assertThat(s.postBirthPulse()).isTrue();
    }

    @ParameterizedTest(name = "systolic {0} scores {1}")
    @CsvSource({"93,2", "94,1", "100,1", "101,0", "135,0", "136,1", "144,1", "145,2"})
    void systolicBands(int value, int points) {
        assertThat(score(normal().systolicBpMmHg(value).build()).points()).containsEntry(Parameter.SYSTOLIC, points);
    }

    @ParameterizedTest(name = "diastolic {0} scores {1}")
    @CsvSource({"56,2", "57,1", "61,1", "62,0", "88,0", "89,1", "96,1", "97,2"})
    void diastolicBands(int value, int points) {
        assertThat(score(normal().diastolicBpMmHg(value).build()).points()).containsEntry(Parameter.DIASTOLIC, points);
    }

    @Test
    @DisplayName("the pulse table switches exactly 48 h after the birth")
    void thePulseTableSwitchesAt48Hours() {
        LocalDateTime birth = LocalDateTime.of(2026, 11, 18, 8, 0);
        assertThat(MaternalEarlyWarning.postBirthPulse(RULES, birth, birth.plusHours(48).minusMinutes(1))).isFalse();
        assertThat(MaternalEarlyWarning.postBirthPulse(RULES, birth, birth.plusHours(48))).isTrue();
        // Still pregnant, or no birth known: the antenatal table.
        assertThat(MaternalEarlyWarning.postBirthPulse(RULES, null, birth)).isFalse();
        // The same pulse of 105: 0 before the switch, 1 after it.
        PatientVitalSign vital = normal().heartRateBpm(105).build();
        assertThat(MaternalEarlyWarning.score(RULES, vital, false).total()).isZero();
        assertThat(MaternalEarlyWarning.score(RULES, vital, true).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("six vital signs, 0 to 12; a set that lacks one is scored on the rest and says so")
    void totalAndCompleteness() {
        Score worst = score(PatientVitalSign.builder().respiratoryRateBpm(30).spo2Percent(88).temperatureCelsius(39.4)
            .heartRateBpm(140).systolicBpMmHg(80).diastolicBpMmHg(50).build());
        assertThat(worst.total()).isEqualTo(12);
        assertThat(worst.complete()).isTrue();

        Score partograph = score(PatientVitalSign.builder().heartRateBpm(125).systolicBpMmHg(118)
            .diastolicBpMmHg(74).temperatureCelsius(36.8).build());
        assertThat(partograph.total()).isEqualTo(2);
        assertThat(partograph.complete()).isFalse();
        assertThat(partograph.missing()).containsExactly(Parameter.RESPIRATORY_RATE, Parameter.SPO2);

        assertThat(score(PatientVitalSign.builder().weightKg(61.0).build()).empty()).isTrue();
    }

    @ParameterizedTest(name = "a total of {0} is {1}")
    @CsvSource({"0,LOW", "1,LOW", "2,LOW_MEDIUM", "4,LOW_MEDIUM", "5,MEDIUM", "7,MEDIUM", "8,HIGH", "12,HIGH"})
    void theFourLevels(int total, Tier tier) {
        assertThat(MaternalEarlyWarning.tierOf(RULES, total)).isEqualTo(tier);
    }

    @Test
    @DisplayName("review within 30 min (low-medium), 15 min (medium), immediately (high); none at low")
    void reviewTimes() {
        assertThat(MaternalEarlyWarning.reviewWithinMinutes(RULES, Tier.LOW)).isNull();
        assertThat(MaternalEarlyWarning.reviewWithinMinutes(RULES, Tier.LOW_MEDIUM)).isEqualTo(30);
        assertThat(MaternalEarlyWarning.reviewWithinMinutes(RULES, Tier.MEDIUM)).isEqualTo(15);
        assertThat(MaternalEarlyWarning.reviewWithinMinutes(RULES, Tier.HIGH)).isZero();
    }

    @ParameterizedTest(name = "with a concern, a total of {0} is {1}")
    @CsvSource({"0,LOW_MEDIUM", "1,LOW_MEDIUM", "2,LOW_MEDIUM", "3,MEDIUM", "4,MEDIUM", "5,HIGH", "7,HIGH", "8,HIGH"})
    void aConcernRaisesTheLevelNeverThePoints(int total, Tier tier) {
        assertThat(MaternalEarlyWarning.raisedByConcerns(RULES, MaternalEarlyWarning.tierOf(RULES, total), total, true))
            .isEqualTo(tier);
        assertThat(MaternalEarlyWarning.raisedByConcerns(RULES, MaternalEarlyWarning.tierOf(RULES, total), total, false))
            .isEqualTo(MaternalEarlyWarning.tierOf(RULES, total));
    }

    @Test
    @DisplayName("each of the seven additional concerns is a flag: the level rises, the total does not")
    void everyConcernIsAFlag() {
        for (MaternalConcern concern : MaternalConcern.values()) {
            Score s = score(normal().maternalConcerns(MaternalConcern.join(List.of(concern))).build());
            assertThat(s.total()).as(concern.name()).isZero();
            assertThat(s.scoreTier()).isEqualTo(Tier.LOW);
            assertThat(s.tier()).as(concern.name()).isEqualTo(Tier.LOW_MEDIUM);
            assertThat(s.concerns()).containsExactly(concern);
            assertThat(s.needsEscalation()).isTrue();
        }
        assertThat(score(normal().build()).needsEscalation()).isFalse();
    }

    @Test
    @DisplayName("oxygen and a lowered consciousness on the set are concerns too; « alert » is not")
    void oxygenAndConsciousnessAreReadFromTheSet() {
        assertThat(score(normal().onOxygen(true).build()).concerns())
            .containsExactly(MaternalConcern.ADDITIONAL_THERAPY);
        assertThat(score(normal().consciousnessLevel(ConsciousnessLevel.VOICE).build()).concerns())
            .containsExactly(MaternalConcern.DECREASED_CONSCIOUSNESS);
        assertThat(score(normal().onOxygen(false).consciousnessLevel(ConsciousnessLevel.ALERT).build()).concerns())
            .isEmpty();
        // A score of 3 with a concern is answered one level up: medium.
        Score raised = score(normal().respiratoryRateBpm(25).temperatureCelsius(37.3).onOxygen(true).build());
        assertThat(raised.total()).isEqualTo(3);
        assertThat(raised.scoreTier()).isEqualTo(Tier.LOW_MEDIUM);
        assertThat(raised.tier()).isEqualTo(Tier.MEDIUM);
    }

    @Test
    @DisplayName("the round: routine at low, 30 min at low-medium or with a concern, 15 min above")
    void observationIntervals() {
        assertThat(MaternalEarlyWarning.observationInterval(RULES, score(normal().build()), ROUTINE))
            .isEqualTo(ROUTINE);
        assertThat(MaternalEarlyWarning.observationInterval(RULES, score(normal().heartRateBpm(125).build()), ROUTINE))
            .isEqualTo(Duration.ofMinutes(30));
        assertThat(MaternalEarlyWarning.observationInterval(RULES,
            score(normal().maternalConcerns("STAFF_CONCERNED").build()), ROUTINE)).isEqualTo(Duration.ofMinutes(30));
        assertThat(MaternalEarlyWarning.observationInterval(RULES,
            score(normal().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(37.4).build()), ROUTINE))
            .isEqualTo(Duration.ofMinutes(15));
        assertThat(MaternalEarlyWarning.observationInterval(RULES,
            score(normal().heartRateBpm(125).respiratoryRateBpm(26).temperatureCelsius(38.0).spo2Percent(90).build()),
            ROUTINE)).isEqualTo(Duration.ofMinutes(15));
        // The score only ever shortens a round.
        assertThat(MaternalEarlyWarning.observationInterval(RULES, score(normal().heartRateBpm(125).build()),
            Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
        assertThat(MaternalEarlyWarning.observationInterval(RULES, null, ROUTINE)).isEqualTo(ROUTINE);
    }

    @Test
    @DisplayName("CRADLE shock index: yellow from 0.9, red from 1.7 (heart rate ÷ systolic)")
    void shockIndexEdges() {
        // 89 / 100 = 0.89 green; 90 / 100 = 0.9 yellow; 169 / 100 = 1.69 yellow; 170 / 100 = 1.7 red.
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 89, 100, 70)).isEmpty();
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 90, 100, 70)).containsExactly(HardAlert.SHOCK);
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 169, 100, 70)).containsExactly(HardAlert.SHOCK);
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 170, 100, 70)).containsExactly(HardAlert.SEVERE_SHOCK);
        assertThat(HardAlert.SHOCK.light()).isEqualTo(MaternalEarlyWarning.Light.YELLOW);
        assertThat(HardAlert.SEVERE_SHOCK.light()).isEqualTo(MaternalEarlyWarning.Light.RED);
        // A missing value, or a pressure of zero, never alerts.
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, null, 100, 70)).isEmpty();
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 120, null, null)).isEmpty();
        assertThat(MaternalEarlyWarning.shockIndex(120, 0)).isNull();
        assertThat(MaternalEarlyWarning.shockIndex(90, 100)).isEqualTo(0.9);
    }

    @Test
    @DisplayName("CRADLE red pressure is the shared obstetric rule: 160 systolic OR 110 diastolic, 159/109 is not")
    void theRedPressureBandIsTheSharedRule() {
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 80, 159, 109)).isEmpty();
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 80, 160, 80)).containsExactly(HardAlert.SEVERE_HYPERTENSION);
        assertThat(MaternalEarlyWarning.hardAlerts(RULES, 80, 150, 110)).containsExactly(HardAlert.SEVERE_HYPERTENSION);
        assertThat(HardAlert.SEVERE_HYPERTENSION.light()).isEqualTo(MaternalEarlyWarning.Light.RED);
    }

    @Test
    @DisplayName("170/115 alone scores only low-medium; the red alert has it answered as high")
    void aRedAlertIsAnsweredAsHighWhateverTheScore() {
        Score s = score(normal().systolicBpMmHg(170).diastolicBpMmHg(115).build());
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.tier()).isEqualTo(Tier.LOW_MEDIUM);
        assertThat(s.alerts()).containsExactly(HardAlert.SEVERE_HYPERTENSION);
        assertThat(s.red()).isTrue();
        assertThat(s.responseTier()).isEqualTo(Tier.HIGH);
        assertThat(MaternalEarlyWarning.observationInterval(RULES, s, ROUTINE)).isEqualTo(Duration.ofMinutes(15));

        // A yellow shock index alone raises no level: it is shown, and the score decides.
        Score yellow = score(normal().heartRateBpm(110).systolicBpMmHg(118).build());
        assertThat(yellow.alerts()).containsExactly(HardAlert.SHOCK);
        assertThat(yellow.total()).isZero();
        assertThat(yellow.responseTier()).isEqualTo(Tier.LOW);
    }

    @ParameterizedTest(name = "labour pulse {0}: {1}")
    @CsvSource({"59,PULSE_LOW", "60,", "119,", "120,PULSE_HIGH"})
    void labourPulseAlertValues(int pulse, LabourAlert expected) {
        assertLabour(MaternalEarlyWarning.labourAlerts(RULES, pulse, null, null, null), expected);
    }

    @ParameterizedTest(name = "labour systolic {0}: {1}")
    @CsvSource({"79,SYSTOLIC_LOW", "80,", "139,", "140,SYSTOLIC_HIGH"})
    void labourSystolicAlertValues(int systolic, LabourAlert expected) {
        assertLabour(MaternalEarlyWarning.labourAlerts(RULES, null, systolic, null, null), expected);
    }

    @ParameterizedTest(name = "labour diastolic {0}: {1}")
    @CsvSource({"89,", "90,DIASTOLIC_HIGH"})
    void labourDiastolicAlertValue(int diastolic, LabourAlert expected) {
        assertLabour(MaternalEarlyWarning.labourAlerts(RULES, null, null, diastolic, null), expected);
    }

    @ParameterizedTest(name = "labour temperature {0}: {1}")
    @CsvSource({"34.9,TEMPERATURE_LOW", "35.0,", "37.4,", "37.5,TEMPERATURE_HIGH"})
    void labourTemperatureAlertValues(double temperature, LabourAlert expected) {
        assertLabour(MaternalEarlyWarning.labourAlerts(RULES, null, null, null, temperature), expected);
    }

    private static void assertLabour(List<LabourAlert> alerts, LabourAlert expected) {
        if (expected == null) {
            assertThat(alerts).isEmpty();
        } else {
            assertThat(alerts).containsExactly(expected);
        }
    }

    @Test
    @DisplayName("a changed rule set changes the answer: the thresholds are read, not written in the calculator")
    void theRuleSetIsConfigurable() {
        MaternalEarlyWarningRules local = new MaternalEarlyWarningRules();
        local.getMews().setPulse(new MaternalEarlyWarningRules.Band(50.0, 55.0, 130.0, 140.0));
        local.getCradle().setShockIndexYellowFrom(1.0);
        local.getLabour().setPulseHighFrom(110);
        assertThat(MaternalEarlyWarning.score(local, normal().heartRateBpm(125).build(), false).total()).isZero();
        assertThat(MaternalEarlyWarning.hardAlerts(local, 95, 100, 70)).isEmpty();
        assertThat(MaternalEarlyWarning.labourAlerts(local, 112, null, null, null))
            .containsExactly(LabourAlert.PULSE_HIGH);
    }
}
