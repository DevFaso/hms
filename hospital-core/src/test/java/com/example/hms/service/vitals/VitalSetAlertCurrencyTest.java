package com.example.hms.service.vitals;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.PatientVitalSignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D4103 (r81): when an urgent alert raised from a vital set stops being escalated. A later reading that
 * re-measured the alert's values and that the raising rule no longer judges urgent closes it; a reading that left
 * a value out says nothing; a later urgent reading keeps it. The thresholds are the raising rules' own.
 */
class VitalSetAlertCurrencyTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 27, 5, 31);

    private final PatientVitalSignRepository vitals = mock(PatientVitalSignRepository.class);
    private final List<PatientVitalSign> later = new ArrayList<>();
    private final Patient baby = new Patient();
    private VitalSetAlertCurrency currency;

    @BeforeEach
    void setUp() {
        currency = new VitalSetAlertCurrency(vitals);
        currency.setMaternalRules(new MaternalEarlyWarningRules());
        baby.setDateOfBirth(T0.toLocalDate());
    }

    private PatientVitalSign source(PatientVitalSign.PatientVitalSignBuilder builder) {
        PatientVitalSign source = set(T0, builder);
        when(vitals.findById(source.getId())).thenReturn(Optional.of(source));
        // The repository answers newest first, the source included (from = its own time).
        when(vitals.findWithinRange(eq(PATIENT), eq(HOSPITAL), eq(T0), any(), any())).thenAnswer(call -> {
            List<PatientVitalSign> all = new ArrayList<>(later);
            all.add(source);
            all.sort(Comparator.comparing(PatientVitalSign::getRecordedAt).reversed());
            return all;
        });
        return source;
    }

    private PatientVitalSign set(LocalDateTime at, PatientVitalSign.PatientVitalSignBuilder builder) {
        PatientVitalSign vital = builder.recordedAt(at).patient(baby).build();
        vital.setId(UUID.randomUUID());
        return vital;
    }

    /** The r81 newborn set: 35,4 °C, 185 bpm, 70/min, 89 %: four URGENT findings. */
    private static PatientVitalSign.PatientVitalSignBuilder newbornDanger() {
        return PatientVitalSign.builder().temperatureCelsius(35.4).heartRateBpm(185).respiratoryRateBpm(70)
            .spo2Percent(89);
    }

    /** The r81 normal set: 36,8 °C, 140 bpm, 44/min, 97 %. */
    private static PatientVitalSign.PatientVitalSignBuilder newbornNormal() {
        return PatientVitalSign.builder().temperatureCelsius(36.8).heartRateBpm(140).respiratoryRateBpm(44)
            .spo2Percent(97);
    }

    @Test
    void aLaterNormalNewbornSetClosesEachOfTheFourFindings() {
        PatientVitalSign source = source(newbornDanger());
        later.add(set(T0.plusMinutes(2), newbornNormal()));

        for (String type : List.of("THERMOREGULATION", "CARDIOVASCULAR", "RESPIRATORY", "OXYGENATION")) {
            assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, type)).as(type).isTrue();
        }
    }

    @Test
    void aNewbornFindingStandsWithNoLaterSet() {
        PatientVitalSign source = source(newbornDanger());

        assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, "OXYGENATION")).isFalse();
    }

    @Test
    void aLaterSetThatDidNotMeasureTheValueSaysNothing() {
        PatientVitalSign source = source(newbornDanger());
        // A temperature alone: the saturation was not measured again.
        later.add(set(T0.plusMinutes(2), PatientVitalSign.builder().temperatureCelsius(36.8)));

        assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, "OXYGENATION")).isFalse();
        assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, "THERMOREGULATION")).isTrue();
    }

    @Test
    void aNewbornFindingStandsWhenANewerSetIsUrgentAgain() {
        PatientVitalSign source = source(newbornDanger());
        later.add(set(T0.plusMinutes(2), newbornNormal()));
        later.add(set(T0.plusMinutes(10), PatientVitalSign.builder().spo2Percent(86)));

        assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, "OXYGENATION")).isFalse();
    }

    @Test
    void aNormalSetAtTheSameRecordedTimeAsAnUrgentOneDoesNotClose() {
        PatientVitalSign source = source(newbornDanger());
        later.add(set(T0.plusMinutes(5), newbornNormal()));
        later.add(set(T0.plusMinutes(5), PatientVitalSign.builder().spo2Percent(86)));

        assertThat(currency.newbornFindingResolved(source.getId(), PATIENT, HOSPITAL, "OXYGENATION")).isFalse();
    }

    @Test
    void aSourceThatCannotBeReadKeepsTheAlert() {
        assertThat(currency.newbornFindingResolved(UUID.randomUUID(), PATIENT, HOSPITAL, "OXYGENATION")).isFalse();
        assertThat(currency.urgentHypertensionResolved(UUID.randomUUID(), PATIENT, HOSPITAL)).isFalse();
        assertThat(currency.maternalWarningResolved(UUID.randomUUID(), PATIENT, HOSPITAL)).isFalse();
    }

    @Test
    void aLaterPressureBelowTheAdultSevereRangeClosesUrgentHypertension() {
        // r81: 192/124 with a sign of organ damage, then 132/84.
        PatientVitalSign source = source(PatientVitalSign.builder().systolicBpMmHg(192).diastolicBpMmHg(124));
        later.add(set(T0.plusMinutes(49), PatientVitalSign.builder().systolicBpMmHg(132).diastolicBpMmHg(84)));

        assertThat(currency.urgentHypertensionResolved(source.getId(), PATIENT, HOSPITAL)).isTrue();
    }

    @Test
    void urgentHypertensionStandsAtTheThresholdAndWithoutBothPressures() {
        PatientVitalSign source = source(PatientVitalSign.builder().systolicBpMmHg(192).diastolicBpMmHg(124));
        // The systolic alone, however low, did not re-measure the diastolic.
        later.add(set(T0.plusMinutes(10), PatientVitalSign.builder().systolicBpMmHg(130)));
        assertThat(currency.urgentHypertensionResolved(source.getId(), PATIENT, HOSPITAL)).isFalse();

        // 180 systolic is still in the adult severe range (the rule is inclusive).
        later.add(set(T0.plusMinutes(20), PatientVitalSign.builder()
            .systolicBpMmHg(SevereHypertension.ADULT_SYSTOLIC).diastolicBpMmHg(90)));
        assertThat(currency.urgentHypertensionResolved(source.getId(), PATIENT, HOSPITAL)).isFalse();

        later.add(set(T0.plusMinutes(30), PatientVitalSign.builder()
            .systolicBpMmHg(SevereHypertension.ADULT_SYSTOLIC - 1).diastolicBpMmHg(SevereHypertension.ADULT_DIASTOLIC - 1)));
        assertThat(currency.urgentHypertensionResolved(source.getId(), PATIENT, HOSPITAL)).isTrue();
    }

    /** A maternal score of 6 (medium): respiratory rate 25, SpO2 92 %, 39,0 °C. */
    private static PatientVitalSign.PatientVitalSignBuilder maternalMedium() {
        return PatientVitalSign.builder().respiratoryRateBpm(25).spo2Percent(92).temperatureCelsius(39.0)
            .heartRateBpm(80).systolicBpMmHg(118).diastolicBpMmHg(74);
    }

    private static PatientVitalSign.PatientVitalSignBuilder maternalNormal() {
        return PatientVitalSign.builder().respiratoryRateBpm(16).spo2Percent(98).temperatureCelsius(36.8)
            .heartRateBpm(80).systolicBpMmHg(118).diastolicBpMmHg(74);
    }

    @Test
    void aLaterCompleteLowMaternalScoreClosesTheWarning() {
        PatientVitalSign source = source(maternalMedium());
        assertThat(MaternalEarlyWarningService.alertTypeOf(
            MaternalEarlyWarning.score(new MaternalEarlyWarningRules(), source, false))).isNotNull();
        later.add(set(T0.plusMinutes(20), maternalNormal()));

        assertThat(currency.maternalWarningResolved(source.getId(), PATIENT, HOSPITAL)).isTrue();
    }

    @Test
    void anIncompleteMaternalSetSaysNothingAndALaterWarningKeepsIt() {
        PatientVitalSign source = source(maternalMedium());
        later.add(set(T0.plusMinutes(10), PatientVitalSign.builder().heartRateBpm(80)));
        assertThat(currency.maternalWarningResolved(source.getId(), PATIENT, HOSPITAL)).isFalse();

        later.add(set(T0.plusMinutes(20), maternalNormal()));
        later.add(set(T0.plusMinutes(30), maternalMedium()));
        assertThat(currency.maternalWarningResolved(source.getId(), PATIENT, HOSPITAL)).isFalse();
    }

    @Test
    void aMaternalWarningStandsWhenTheScoreIsSwitchedOff() {
        PatientVitalSign source = source(maternalMedium());
        later.add(set(T0.plusMinutes(20), maternalNormal()));
        currency.setMaternalRules(null);

        assertThat(currency.maternalWarningResolved(source.getId(), PATIENT, HOSPITAL)).isFalse();
    }
}
