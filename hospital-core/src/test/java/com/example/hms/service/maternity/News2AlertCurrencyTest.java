package com.example.hms.service.maternity;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.repository.LaborPartographEntryRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PostpartumObservationRepository;
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
 * QA D3862, review of #1339 (Rex P1): when a HIGH NEWS2 alert stops reminding. Only a complete reading below HIGH
 * that is newer than every HIGH reading since the alert closes it; a HIGH reading, complete or not, keeps it standing.
 */
class News2AlertCurrencyTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 25, 10, 0);

    private final PatientVitalSignRepository vitals = mock(PatientVitalSignRepository.class);
    private final List<PatientVitalSign> later = new ArrayList<>();
    private ObstetricAlertCurrency currency;
    private ObstetricAlertEscalation row;

    @BeforeEach
    void setUp() {
        currency = new ObstetricAlertCurrency(mock(LaborPartographEntryRepository.class),
            mock(NewbornAssessmentRepository.class), mock(PostpartumObservationRepository.class),
            mock(com.example.hms.repository.DeliveryRecordRepository.class), mock(StillbornNeonatalAlerts.class));
        currency.setVitalSignRepository(vitals);
        PatientVitalSign source = completeHigh(T0);
        when(vitals.findById(source.getId())).thenReturn(Optional.of(source));
        // The repository answers newest first, the source included (from = its own time).
        when(vitals.findWithinRange(eq(PATIENT), eq(HOSPITAL), eq(T0), any(), any())).thenAnswer(call -> {
            List<PatientVitalSign> all = new ArrayList<>(later);
            all.add(source);
            all.sort(Comparator.comparing(PatientVitalSign::getRecordedAt).reversed());
            return all;
        });
        row = ObstetricAlertEscalation.builder().hospitalId(HOSPITAL).patientId(PATIENT)
            .sourceType(ObstetricAlertEscalationService.SOURCE_NEWS2).sourceId(source.getId()).alertType("NEWS2_HIGH")
            .build();
    }

    /** NEWS2 15: RR 32, SpO2 85 on air, BP 82, HR 125, T 38.5, new confusion. */
    private static PatientVitalSign completeHigh(LocalDateTime at) {
        return set(at, PatientVitalSign.builder().respiratoryRateBpm(32).spo2Percent(85).onOxygen(false)
            .systolicBpMmHg(82).heartRateBpm(125).temperatureCelsius(38.5)
            .consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.NEW_CONFUSION));
    }

    /** NEWS2 0, every parameter measured. */
    private static PatientVitalSign completeLow(LocalDateTime at) {
        return set(at, PatientVitalSign.builder().respiratoryRateBpm(16).spo2Percent(97).onOxygen(false)
            .systolicBpMmHg(125).heartRateBpm(75).temperatureCelsius(37.0)
            .consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.ALERT));
    }

    /** RR 32, SpO2 85, BP 82 only: a partial total of 9, HIGH whatever the missing values. */
    private static PatientVitalSign incompleteHigh(LocalDateTime at) {
        return set(at, PatientVitalSign.builder().respiratoryRateBpm(32).spo2Percent(85).systolicBpMmHg(82));
    }

    /** A pulse alone: below HIGH, and says nothing of the rest. */
    private static PatientVitalSign incompleteLow(LocalDateTime at) {
        return set(at, PatientVitalSign.builder().heartRateBpm(80));
    }

    private static PatientVitalSign set(LocalDateTime at, PatientVitalSign.PatientVitalSignBuilder builder) {
        PatientVitalSign vital = builder.recordedAt(at).build();
        vital.setId(UUID.randomUUID());
        return vital;
    }

    @Test
    void highThenCompleteLowThenIncompleteHighKeepsTheAlertStanding() {
        // Rex P1: the newest reading is HIGH; the older complete LOW must not close the alert.
        later.add(completeLow(T0.plusMinutes(15)));
        later.add(incompleteHigh(T0.plusMinutes(30)));

        assertThat(currency.noLongerUrgent(row)).isFalse();
    }

    @Test
    void aCompleteLowNewerThanEveryHighClosesIt() {
        later.add(incompleteHigh(T0.plusMinutes(10)));
        later.add(completeLow(T0.plusMinutes(25)));

        assertThat(currency.noLongerUrgent(row)).isTrue();
    }

    @Test
    void aPartialLowReadingSaysNothing() {
        later.add(incompleteLow(T0.plusMinutes(10)));
        assertThat(currency.noLongerUrgent(row)).isFalse();

        // ...and does not hide a complete LOW before it either.
        later.add(completeLow(T0.plusMinutes(5)));
        assertThat(currency.noLongerUrgent(row)).isTrue();
    }

    @Test
    void aCompleteHighAfterTheLowKeepsItStanding() {
        later.add(completeLow(T0.plusMinutes(15)));
        later.add(completeHigh(T0.plusMinutes(30)));

        assertThat(currency.noLongerUrgent(row)).isFalse();
    }

    @Test
    void theVitalSetSourcesOfTheOtherScoresNeverCloseOnTheirOwn() {
        // The maternal and newborn vital-set alerts stand until acknowledged or the cap: no reading closes them.
        later.add(completeLow(T0.plusMinutes(15)));
        for (String source : List.of(ObstetricAlertEscalationService.SOURCE_VITAL_SIGN,
                ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN)) {
            ObstetricAlertEscalation other = ObstetricAlertEscalation.builder().hospitalId(HOSPITAL).patientId(PATIENT)
                .sourceType(source).sourceId(row.getSourceId()).alertType("X").build();
            assertThat(currency.noLongerUrgent(other)).as(source).isFalse();
        }
    }
}
