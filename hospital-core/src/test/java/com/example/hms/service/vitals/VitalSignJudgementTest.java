package com.example.hms.service.vitals;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.NewbornVitalAlertDTO;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.maternity.NewbornAlertRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D1564 (round 43, nurse-triage-inpatient n1/n9): a 4-day-old's HR 80, 35.0 °C and 37.8 °C
 * saved as not significant, judged with adult limits. The bands are NewbornAlertRules',
 * PROVISIONAL pending the neonatal lead's approval.
 */
class VitalSignJudgementTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    private final NewbornAssessmentRepository assessments = mock(NewbornAssessmentRepository.class);
    private final News2EscalationService news2 = mock(News2EscalationService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final VitalSignJudgement judgement = new VitalSignJudgement(assessments, news2, notifications,
        com.example.hms.i18n.TestMessageSources.bundles());

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Patient bornDaysAgo(int days) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Bebe");
        patient.setLastName("Ouedraogo");
        patient.setDateOfBirth(NOW.toLocalDate().minusDays(days));
        return patient;
    }

    private static PatientVitalSign.PatientVitalSignBuilder set(Patient patient) {
        return PatientVitalSign.builder().patient(patient).recordedAt(NOW);
    }

    private static List<String> codes(VitalSignJudgement.Judgement j) {
        return j.newbornFindings().stream().map(NewbornAlertRules.Finding::code).toList();
    }

    @Test
    @DisplayName("D1564: HR 80, 35.0 °C and 37.8 °C on a 4-day-old are significant (adult limits passed them)")
    void theRound43ReadingsAreSignificantForANewborn() {
        Patient baby = bornDaysAgo(4);
        for (PatientVitalSign vital : List.of(set(baby).heartRateBpm(80).build(),
                set(baby).temperatureCelsius(35.0).build(), set(baby).temperatureCelsius(37.8).build())) {
            VitalSignJudgement.Judgement j = judgement.judge(vital);
            assertThat(j.newborn()).isTrue();
            assertThat(vital.isClinicallySignificant()).as(vital.toString()).isTrue();
        }
        assertThat(codes(judgement.judge(set(baby).heartRateBpm(80).build()))).contains("newborn-heart-rate");
        assertThat(codes(judgement.judge(set(baby).temperatureCelsius(35.0).build())))
            .contains("newborn-temperature-critical");
        assertThat(codes(judgement.judge(set(baby).temperatureCelsius(37.8).build())))
            .contains("newborn-temperature-who-danger");
    }

    @Test
    @DisplayName("D1564: HR 190, RR 70 and SpO2 88 raise the newborn findings, not only the adult flag")
    void theHighReadingsRaiseTheNewbornFindings() {
        Patient baby = bornDaysAgo(4);
        assertThat(codes(judgement.judge(set(baby).heartRateBpm(190).build())))
            .contains("newborn-heart-rate", "newborn-reference-heart-rate");
        assertThat(codes(judgement.judge(set(baby).respiratoryRateBpm(70).build()))).contains("newborn-respiratory");
        assertThat(codes(judgement.judge(set(baby).spo2Percent(88).build()))).contains("newborn-oxygenation");
    }

    @Test
    void aNormalNewbornSetIsNotSignificant() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(140).respiratoryRateBpm(45)
            .temperatureCelsius(36.8).spo2Percent(97).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(j.newborn()).isTrue();
        assertThat(j.newbornFindings()).isEmpty();
        assertThat(vital.isClinicallySignificant()).isFalse();
    }

    @Test
    void aReferenceRangeNoticeAloneIsNotSignificant() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(vital))).containsExactly("newborn-reference-heart-rate");
        assertThat(vital.isClinicallySignificant()).isFalse();
    }

    @Test
    @DisplayName("an extreme newborn reading is saved with its warnings: never refused")
    void anExtremeReadingIsNeverRefused() {
        PatientVitalSign vital = set(bornDaysAgo(1)).heartRateBpm(320).respiratoryRateBpm(200).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);
        assertThat(codes(j)).contains("newborn-check-heart-rate", "newborn-check-respiratory");
        assertThat(vital.isClinicallySignificant()).isTrue();
    }

    @Test
    @DisplayName("28 days and older keep the per-vital limits; HR 80 is not significant for an adult")
    void otherAgesKeepTheirRules() {
        PatientVitalSign dayTwentyEight = set(bornDaysAgo(28)).heartRateBpm(80).build();
        assertThat(judgement.judge(dayTwentyEight).newborn()).isFalse();
        assertThat(dayTwentyEight.isClinicallySignificant()).isFalse();

        Patient adult = new Patient();
        adult.setDateOfBirth(LocalDate.of(1980, 1, 1));
        PatientVitalSign fast = set(adult).heartRateBpm(160).build();
        assertThat(judgement.judge(fast).newborn()).isFalse();
        assertThat(fast.isClinicallySignificant()).as("HR above 150, the station's limit").isTrue();

        PatientVitalSign unknownAge = set(new Patient()).heartRateBpm(80).build();
        assertThat(judgement.judge(unknownAge).newborn()).as("no date of birth: never a newborn").isFalse();
    }

    @Test
    void aCallersFlagIsNeverCleared() {
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(140).clinicallySignificant(true).build();
        judgement.judge(vital);
        assertThat(vital.isClinicallySignificant()).isTrue();
    }

    @Test
    void pretermIsReadFromThisHospitalsDeliveryRecord() {
        Patient baby = bornDaysAgo(4);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        NewbornAssessment assessment = NewbornAssessment.builder().hospital(hospital)
            .deliveryRecord(DeliveryRecord.builder().gestationalAgeWeeksAtBirth(33).build()).build();
        when(assessments.findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(baby.getId()))
            .thenReturn(Optional.of(assessment));
        PatientVitalSign vital = set(baby).hospital(hospital).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(vital))).containsExactly("newborn-reference-heart-rate-preterm");

        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PatientVitalSign elsewhere = set(baby).hospital(other).heartRateBpm(115).build();
        assertThat(codes(judgement.judge(elsewhere))).containsExactly("newborn-reference-heart-rate");
    }

    @Test
    @DisplayName("each urgent finding notifies the recorder and the physicians, as the newborn assessment does")
    void urgentFindingsNotifyTheTeam() {
        Patient baby = bornDaysAgo(4);
        UUID hospitalId = UUID.randomUUID();
        Set<String> recipients = new LinkedHashSet<>(List.of("nurse_a", "dr_a"));
        when(news2.recipients(baby.getId(), hospitalId, "nurse_a", null)).thenReturn(recipients);
        PatientVitalSign vital = set(baby).heartRateBpm(80).spo2Percent(88).build();
        VitalSignJudgement.Judgement j = judgement.judge(vital);

        judgement.escalate(vital, j, baby.getId(), hospitalId, "nurse_a", null);

        verify(news2).escalateIfNeeded(vital, baby.getId(), hospitalId, "nurse_a", null);
        // Two urgent findings (heart rate, oxygenation) to two people.
        verify(notifications, times(4)).createNotification(eq("newborn.alert.notification"), any(),
            anyString(), eq("MATERNITY_ALERT"));
        verify(notifications, times(2)).createNotification(eq("newborn.alert.notification"), any(),
            eq("dr_a"), eq("MATERNITY_ALERT"));
    }

    @Test
    void nothingUrgentNotifiesNobody() {
        Patient baby = bornDaysAgo(4);
        PatientVitalSign vital = set(baby).heartRateBpm(115).build();
        judgement.escalate(vital, judgement.judge(vital), baby.getId(), UUID.randomUUID(), "nurse_a", null);
        verify(notifications, never()).createNotification(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theAlertsAreInTheRecordersLanguage() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PatientVitalSign vital = set(bornDaysAgo(4)).heartRateBpm(80).build();
        List<NewbornVitalAlertDTO> alerts = judgement.alerts(judgement.judge(vital));
        assertThat(alerts).extracting(NewbornVitalAlertDTO::getCode).contains("newborn-heart-rate");
        NewbornVitalAlertDTO urgent = alerts.stream().filter(a -> "newborn-heart-rate".equals(a.getCode()))
            .findFirst().orElseThrow();
        assertThat(urgent.getSeverity()).isEqualTo("URGENT");
        assertThat(urgent.getMessage()).isNotBlank().isNotEqualTo("newborn-heart-rate").contains("160");
        assertThat(judgement.alerts(judgement.judge(set(bornDaysAgo(400)).heartRateBpm(80).build()))).isEmpty();
    }
}
