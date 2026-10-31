package com.example.hms.service.vitals;

import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.utility.NewsScoreCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D207: a NEWS2 of 11 (HIGH) must alert the nurse and the responsible doctor. */
class News2EscalationServiceTest {

    private final AdmissionRepository admissionRepository = mock(AdmissionRepository.class);
    private final com.example.hms.service.RenderingNotificationService notificationService = mock(com.example.hms.service.RenderingNotificationService.class, org.mockito.Answers.CALLS_REAL_METHODS);
    private final StaticMessageSource messages = new StaticMessageSource();
    private final News2EscalationService service =
        new News2EscalationService(admissionRepository, notificationService);

    private static PatientVitalSign qaSet(Patient patient) {
        // NURSE_A's QA set for A3: T 38.9, HR 112, RR 26, BP 95/60, SpO2 91.
        return PatientVitalSign.builder()
            .patient(patient)
            .recordedAt(LocalDateTime.now())
            .temperatureCelsius(38.9).heartRateBpm(112).respiratoryRateBpm(26)
            .systolicBpMmHg(95).diastolicBpMmHg(60).spo2Percent(91)
            .build();
    }

    @Test
    void highScoreAlertsTheNurseAndTheAttendingPhysician() {
        messages.addMessage("news2.escalation.high", Locale.getDefault(), "HIGH {0} {1} {2}");
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        User doctorUser = new User();
        doctorUser.setUsername("dr_a");
        Staff doctor = new Staff();
        doctor.setUser(doctorUser);
        Admission stay = new Admission();
        stay.setHospital(hospital);
        stay.setAttendingPhysician(doctor);
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(patient.getId()), eq(hospitalId), any()))
            .thenReturn(List.of(stay));

        NewsScoreCalculator.NewsScoreResult score =
            service.escalateIfNeeded(qaSet(patient), patient.getId(), hospitalId, "nurse_a");

        assertThat(score.riskBand()).isEqualTo(NewsScoreCalculator.RiskBand.HIGH);
        verify(notificationService).created(anyString(), eq("nurse_a"), eq(News2EscalationService.NOTIFICATION_TYPE));
        verify(notificationService).created(anyString(), eq("dr_a"), eq(News2EscalationService.NOTIFICATION_TYPE));
    }

    @Test
    void aStayAtAnotherHospitalNamesNoPhysician() {
        messages.addMessage("news2.escalation.high", Locale.getDefault(), "HIGH {0} {1} {2}");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Admission stay = new Admission();
        stay.setHospital(other);
        // QA D1065 class: the stay is read at the acting hospital only, so another hospital's is never found.
        service.escalateIfNeeded(qaSet(patient), patient.getId(), UUID.randomUUID(), "nurse_a");

        verify(notificationService).created(anyString(), eq("nurse_a"), anyString());
        verify(notificationService, never()).created(anyString(), eq("dr_a"), anyString());
    }

    @Test
    void aTriageSetWithNoStayAlertsTheNurseAndTheVisitPhysician_D265() {
        messages.addMessage("news2.escalation.high", Locale.getDefault(), "HIGH {0} {1} {2}");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        // QA D265 set: NEWS2 14 (RR 30, SpO2 88, BP 85, HR 135, T 39.5, new confusion).
        PatientVitalSign triage = PatientVitalSign.builder()
            .patient(patient).recordedAt(LocalDateTime.now()).source("TRIAGE")
            .respiratoryRateBpm(30).spo2Percent(88).systolicBpMmHg(85).heartRateBpm(135)
            .temperatureCelsius(39.5).consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.NEW_CONFUSION)
            .build();

        NewsScoreCalculator.NewsScoreResult score =
            service.escalateIfNeeded(triage, patient.getId(), UUID.randomUUID(), "nurse_a", "dr_a");

        assertThat(score.riskBand()).isEqualTo(NewsScoreCalculator.RiskBand.HIGH);
        verify(notificationService).created(anyString(), eq("nurse_a"), eq(News2EscalationService.NOTIFICATION_TYPE));
        verify(notificationService).created(anyString(), eq("dr_a"), eq(News2EscalationService.NOTIFICATION_TYPE));
    }

    @Test
    void aLowScoreAlertsNobody() {
        PatientVitalSign calm = PatientVitalSign.builder()
            .temperatureCelsius(37.0).heartRateBpm(70).respiratoryRateBpm(14)
            .systolicBpMmHg(120).spo2Percent(98).build();

        service.escalateIfNeeded(calm, UUID.randomUUID(), UUID.randomUUID(), "nurse_a");

        verify(notificationService, never()).created(any(), any(), any());
    }

    @Test
    void theObservationIntervalFollowsTheBand() {
        Duration routine = Duration.ofHours(2);
        NewsScoreCalculator.NewsScoreResult high = NewsScoreCalculator.score(qaSet(null));
        assertThat(News2EscalationService.observationInterval(high, routine)).isEqualTo(Duration.ofMinutes(15));
        NewsScoreCalculator.NewsScoreResult zero = new NewsScoreCalculator.NewsScoreResult(
            0, false, NewsScoreCalculator.RiskBand.LOW, java.util.List.of());
        assertThat(News2EscalationService.observationInterval(zero, routine)).isEqualTo(routine);
        NewsScoreCalculator.NewsScoreResult medium = new NewsScoreCalculator.NewsScoreResult(
            5, false, NewsScoreCalculator.RiskBand.MEDIUM, java.util.List.of());
        assertThat(News2EscalationService.observationInterval(medium, routine)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void d518_aChildsVitalsPageNobodyWithTheAdultScore() {
        // QA D518: a 5-year-old's normal heart and breathing rate scored HIGH
        // on the adult table and paged the nurse and the doctor.
        messages.addMessage("news2.escalation.high", Locale.getDefault(), "HIGH {0} {1} {2}");
        Patient child = new Patient();
        child.setId(UUID.randomUUID());
        child.setDateOfBirth(java.time.LocalDate.now().minusYears(5));

        service.escalateIfNeeded(qaSet(child), child.getId(), UUID.randomUUID(), "nurse_a", "dr_a");

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
    }
}
