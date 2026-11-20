package com.example.hms.service.vitals;

import com.example.hms.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Review of #1237: NEWS2 is shown only where it is validated")
class News2ApplicabilityTest {

    private final SevereHypertension severeHypertension = mock(SevereHypertension.class);
    private final News2Applicability applicability = new News2Applicability(severeHypertension);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 20, 1, 0);

    private Patient born(LocalDate dob) {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(dob);
        return p;
    }

    @Test
    void aNewbornIsNotValidated() {
        assertThat(applicability.statusOf(born(now.toLocalDate().minusDays(1)), UUID.randomUUID(), now))
            .isEqualTo(News2Applicability.Status.NEWBORN);
    }

    @Test
    void aChildHasNoScoreAsBefore() {
        News2Applicability.Status status = applicability.statusOf(born(now.toLocalDate().minusYears(5)), UUID.randomUUID(), now);
        assertThat(status).isEqualTo(News2Applicability.Status.CHILD);
        assertThat(status.notValidated()).isFalse();
    }

    @Test
    void aPregnantOrPostpartumWomanIsNotValidated() {
        Patient mother = born(now.toLocalDate().minusYears(28));
        when(severeHypertension.scopeOf(any(), any(), any())).thenReturn(SevereHypertension.Scope.POSTPARTUM);
        assertThat(applicability.statusOf(mother, UUID.randomUUID(), now)).isEqualTo(News2Applicability.Status.OBSTETRIC);
        when(severeHypertension.scopeOf(any(), any(), any())).thenReturn(SevereHypertension.Scope.PREGNANT);
        assertThat(applicability.statusOf(mother, UUID.randomUUID(), now).notValidated()).isTrue();
    }

    @Test
    void anAdultOutsidePregnancyKeepsTheScore() {
        when(severeHypertension.scopeOf(any(), any(), any())).thenReturn(SevereHypertension.Scope.ADULT);
        assertThat(applicability.statusOf(born(now.toLocalDate().minusYears(40)), UUID.randomUUID(), now))
            .isEqualTo(News2Applicability.Status.APPLIES);
    }

    @Test
    void aScoreNeverLengthensTheRoutineRound() {
        java.time.Duration routine = java.time.Duration.ofHours(2);
        com.example.hms.model.PatientVitalSign calm = com.example.hms.model.PatientVitalSign.builder()
            .respiratoryRateBpm(16).spo2Percent(98).onOxygen(false).temperatureCelsius(37.0).systolicBpMmHg(120)
            .heartRateBpm(70).consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.ALERT).build();
        assertThat(News2EscalationService.observationInterval(
            com.example.hms.utility.NewsScoreCalculator.score(calm), routine)).isLessThanOrEqualTo(routine);
    }

    @Test
    void noPatientKeepsTheScoreAndAnUnknownTimeIsNow() {
        assertThat(applicability.statusOf(null, UUID.randomUUID(), now)).isEqualTo(News2Applicability.Status.APPLIES);
        assertThat(applicability.statusOf(born(LocalDate.now().minusDays(2)), UUID.randomUUID(), null))
            .isEqualTo(News2Applicability.Status.NEWBORN);
        assertThat(News2Applicability.Status.APPLIES.notValidated()).isFalse();
        assertThat(News2Applicability.Status.NEWBORN.notValidated()).isTrue();
    }
}
