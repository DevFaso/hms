package com.example.hms.utility;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.utility.NewsScoreCalculator.NewsScoreResult;
import com.example.hms.utility.NewsScoreCalculator.RiskBand;
import org.junit.jupiter.api.Test;

/**
 * NEWS2 scoring (P3 #25b) against the RCP 2017 tables. The parameter
 * band tests pin the exact boundary values — an off-by-one here
 * under-scores a deteriorating patient.
 */
class NewsScoreCalculatorTest {

    /** A fully-normal, fully-complete bundle: every parameter scores 0. */
    private static PatientVitalSign normal() {
        return PatientVitalSign.builder()
            .respiratoryRateBpm(16)
            .spo2Percent(98)
            .onOxygen(false)
            .temperatureCelsius(37.0)
            .systolicBpMmHg(120)
            .heartRateBpm(70)
            .consciousnessLevel(ConsciousnessLevel.ALERT)
            .build();
    }

    @Test
    void aNormalCompleteBundleScoresZeroLowRisk() {
        NewsScoreResult result = NewsScoreCalculator.score(normal());

        assertThat(result.total()).isZero();
        assertThat(result.riskBand()).isEqualTo(RiskBand.LOW);
        assertThat(result.complete()).isTrue();
        assertThat(result.missingParameters()).isEmpty();
    }

    @Test
    void respiratoryRateBands() {
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(8)).isEqualTo(3);
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(9)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(11)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(12)).isZero();
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(20)).isZero();
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(21)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(24)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreRespiratoryRate(25)).isEqualTo(3);
    }

    @Test
    void spo2Bands() {
        assertThat(NewsScoreCalculator.scoreSpo2(91)).isEqualTo(3);
        assertThat(NewsScoreCalculator.scoreSpo2(92)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreSpo2(93)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreSpo2(94)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreSpo2(95)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreSpo2(96)).isZero();
    }

    @Test
    void temperatureBands() {
        assertThat(NewsScoreCalculator.scoreTemperature(35.0)).isEqualTo(3);
        assertThat(NewsScoreCalculator.scoreTemperature(35.5)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreTemperature(36.1)).isZero();
        assertThat(NewsScoreCalculator.scoreTemperature(38.0)).isZero();
        assertThat(NewsScoreCalculator.scoreTemperature(38.5)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreTemperature(39.1)).isEqualTo(2);
    }

    @Test
    void systolicBpBands() {
        assertThat(NewsScoreCalculator.scoreSystolicBp(90)).isEqualTo(3);
        assertThat(NewsScoreCalculator.scoreSystolicBp(91)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreSystolicBp(100)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreSystolicBp(101)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreSystolicBp(110)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreSystolicBp(111)).isZero();
        assertThat(NewsScoreCalculator.scoreSystolicBp(219)).isZero();
        assertThat(NewsScoreCalculator.scoreSystolicBp(220)).isEqualTo(3);
    }

    @Test
    void heartRateBands() {
        assertThat(NewsScoreCalculator.scoreHeartRate(40)).isEqualTo(3);
        assertThat(NewsScoreCalculator.scoreHeartRate(41)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreHeartRate(50)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreHeartRate(51)).isZero();
        assertThat(NewsScoreCalculator.scoreHeartRate(90)).isZero();
        assertThat(NewsScoreCalculator.scoreHeartRate(91)).isEqualTo(1);
        assertThat(NewsScoreCalculator.scoreHeartRate(111)).isEqualTo(2);
        assertThat(NewsScoreCalculator.scoreHeartRate(131)).isEqualTo(3);
    }

    @Test
    void supplementalOxygenAddsTwo() {
        PatientVitalSign vital = normal();
        vital.setOnOxygen(true);

        assertThat(NewsScoreCalculator.score(vital).total()).isEqualTo(2);
    }

    @Test
    void anyNonAlertConsciousnessScoresThree() {
        for (ConsciousnessLevel level : ConsciousnessLevel.values()) {
            PatientVitalSign vital = normal();
            vital.setConsciousnessLevel(level);
            NewsScoreResult result = NewsScoreCalculator.score(vital);
            if (level == ConsciousnessLevel.ALERT) {
                assertThat(result.total()).isZero();
            } else {
                assertThat(result.total()).isEqualTo(3);
                assertThat(result.anyParameterThree()).isTrue();
                assertThat(result.riskBand()).isEqualTo(RiskBand.LOW_MEDIUM);
            }
        }
    }

    @Test
    void aggregateBands() {
        // 5–6 = MEDIUM: RR 21 (2) + HR 111 (2) + temp 38.5 (1) = 5
        PatientVitalSign medium = normal();
        medium.setRespiratoryRateBpm(21);
        medium.setHeartRateBpm(111);
        medium.setTemperatureCelsius(38.5);
        assertThat(NewsScoreCalculator.score(medium).riskBand()).isEqualTo(RiskBand.MEDIUM);

        // ≥7 = HIGH: add SpO2 92 (2) → 7
        medium.setSpo2Percent(92);
        assertThat(NewsScoreCalculator.score(medium).riskBand()).isEqualTo(RiskBand.HIGH);
    }

    @Test
    void missingParametersAreNamedNeverSilentlyZeroed() {
        // Only RR + HR recorded — the pre-V130 reality for every bundle.
        PatientVitalSign partial = PatientVitalSign.builder()
            .respiratoryRateBpm(24)
            .heartRateBpm(120)
            .build();

        NewsScoreResult result = NewsScoreCalculator.score(partial);

        assertThat(result.total()).isEqualTo(4); // 2 + 2 from what IS known
        assertThat(result.complete()).isFalse();
        assertThat(result.missingParameters()).containsExactlyInAnyOrder(
            "spo2", "onOxygen", "temperature", "systolicBp", "consciousness");
    }
}
