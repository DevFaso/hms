package com.example.hms.service.impl;

import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.enums.PostpartumSupportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.PostpartumObservationMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PostpartumCarePlanRepository;
import com.example.hms.repository.PostpartumObservationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import com.example.hms.i18n.TestMessageSources;

@ExtendWith(MockitoExtension.class)
class PostpartumCareServiceImplTest {

    @Mock
    private PostpartumObservationRepository observationRepository;
    @Mock
    private PostpartumCarePlanRepository carePlanRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock
    private com.example.hms.service.pro.ProResponseService proResponseService;
    @Mock
    private com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;

    private PostpartumCareServiceImpl service;
    private UUID patientId;
    private UUID hospitalId;
    private UUID recorderId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new PostpartumCareServiceImpl(
            observationRepository,
            carePlanRepository,
            patientRepository,
            registrationRepository,
            hospitalRepository,
            staffRepository,
            userRepository,
            notificationService,
            new PostpartumObservationMapper(),
            proResponseService,
            recordAccessPolicy,
            reachRecorder,
            TestMessageSources.bundles(),
            deliveryRecordRepository
        );

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        recorderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Test");
        patient.setLastName("Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setEmail("test.patient@example.com");
        patient.setPhoneNumberPrimary("1234567890");
        patient.setHospitalId(hospitalId);

        hospital = Hospital.builder()
            .name("Demo Hospital")
            .code("DEMO")
            .build();
        hospital.setId(hospitalId);

        User recorder = User.builder()
            .username("nurse.jane")
            .email("nurse.jane@example.com")
            .passwordHash("hash")
            .phoneNumber("0000000000")
            .build();
        recorder.setId(recorderId);

        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(staffRepository.findByUserIdAndHospitalId(recorderId, hospitalId)).thenReturn(Optional.empty());
        lenient().when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId)).thenReturn(Optional.empty());
        lenient().when(notificationService.createNotification(anyString(), anyString())).thenReturn(null);
        lenient().when(observationRepository.save(any(PostpartumObservation.class))).thenAnswer(invocation -> {
            PostpartumObservation obs = invocation.getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });
        lenient().when(carePlanRepository.save(any(PostpartumCarePlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordObservationWithHemorrhageIndicatorsEmitsUrgentAlert() {
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .excessiveBleeding(true)
            .estimatedBloodLossMl(650)
            .educationTopics(Set.of(PostpartumEducationTopic.WARNING_SIGNS_FEVER))
            .educationCompleted(Boolean.TRUE)
            .build();

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertFalse(response.getAlerts().isEmpty(), "Expected hemorrhage alert to be generated");
        assertEquals(PostpartumAlertType.HEMORRHAGE, response.getAlerts().get(0).getType());
        assertEquals(PostpartumAlertSeverity.URGENT, response.getAlerts().get(0).getSeverity());
        assertEquals("postpartum-hemorrhage", response.getAlerts().get(0).getCode());
        // QA D226: the alert body is the staff-locale (French) text, not English composed in code.
        assertTrue(response.getAlerts().get(0).getMessage().startsWith("Risque d"));
        assertTrue(response.getAlerts().get(0).getMessage().toLowerCase().contains("hémorragie"));

    verify(notificationService, times(1)).createNotification(anyString(), anyString());

        ArgumentCaptor<PostpartumObservation> observationCaptor = ArgumentCaptor.forClass(PostpartumObservation.class);
        verify(observationRepository).save(observationCaptor.capture());
        PostpartumObservation savedObservation = observationCaptor.getValue();
        assertTrue(savedObservation.isHemorrhageProtocolActivated(), "Hemorrhage protocol should be activated on alert");
    }

    @Test
    void recordObservationReusesExistingPlanAndSchedulesImmediateFollowUp() {
        PostpartumCarePlan existingPlan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        existingPlan.setId(UUID.randomUUID());
        existingPlan.setImmediateObservationTarget(4);
        existingPlan.setImmediateObservationsCompleted(0);
        existingPlan.setActive(true);
        existingPlan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(existingPlan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(response.getAlerts().isEmpty(), "Should not trigger alerts for stable observation");
        assertEquals(1, existingPlan.getImmediateObservationsCompleted(), "Immediate observation count should increment");
        assertFalse(existingPlan.isImmediateWindowCompleted(), "Immediate window should remain open");
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, existingPlan.getActivePhase());
        assertNotNull(existingPlan.getNextDueAt(), "Next due check should be scheduled");
        assertEquals(existingPlan.getNextDueAt(), response.getSchedule().getNextDueAt());
        assertEquals(observationTime.plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES), existingPlan.getNextDueAt());
    verify(notificationService, never()).createNotification(anyString(), anyString());
    }

    @Test
    void recordObservationEscalatesToEnhancedMonitoringOnInfectionAlert() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateWindowCompleted(true);
        plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        plan.setShiftFrequencyMinutes(PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .temperatureCelsius(38.5)
            .foulLochiaOdor(Boolean.TRUE)
            .painScore(8)
            .moodStatus(PostpartumMoodStatus.TEARFUL)
            .supportStatus(PostpartumSupportStatus.LIMITED)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertFalse(response.getAlerts().isEmpty(), "Expected infection alert");
        int expectedFrequency = Math.max(PostpartumCarePlan.MIN_SHIFT_FREQUENCY_MINUTES,
            PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES / 2);
        assertEquals(expectedFrequency, plan.getShiftFrequencyMinutes(),
            "Shift frequency should tighten during enhanced monitoring");
        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
    assertTrue(plan.isMentalHealthReferralOutstanding(), "Psychosocial referral should be tracked");
    assertTrue(plan.isSocialSupportReferralOutstanding(), "Support referral should be tracked");
    assertTrue(plan.isPainFollowupOutstanding(), "Pain follow-up should be tracked");

        assertTrue(response.getAlerts().stream().anyMatch(alert -> alert.getType() == PostpartumAlertType.INFECTION));
        verify(notificationService, times(1)).createNotification(any(), anyString());
    }

    @Test
    void recordObservationCompletesImmediateWindowWhenStabilized() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateObservationTarget(2);
        plan.setImmediateObservationsCompleted(1);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        int requestedFrequency = 360;
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .stabilizationConfirmed(Boolean.TRUE)
            .shiftFrequencyMinutes(requestedFrequency)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(plan.isImmediateWindowCompleted(), "Immediate window should be closed after stabilization");
        assertEquals(PostpartumSchedulePhase.SHIFT_BASELINE, plan.getActivePhase(),
            "Plan should transition to shift baseline monitoring");
        assertEquals(requestedFrequency, plan.getShiftFrequencyMinutes());
        assertEquals(observationTime.plusMinutes(requestedFrequency), plan.getNextDueAt());
        assertTrue(response.getSchedule().isImmediateWindowComplete());
    }

    @Test
    void recordObservationWithNullRequestThrowsBusinessException() {
        assertThrows(BusinessException.class, () -> service.recordObservation(patientId, null, recorderId));
        verify(observationRepository, never()).save(any());
    }

    @Test
    void getRecentObservationsWithoutALocalPlanReadTheReadableSet() {
        // E9 #59d — no local care plan: the observations span the policy's
        // readable set at the database.
        UUID otherHospitalId = UUID.randomUUID();
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(observationRepository.findByPatient_IdAndHospital_IdInOrderByObservationTimeDesc(
                eq(patientId), eq(Set.of(hospitalId, otherHospitalId)), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of());

        assertThat(service.getRecentObservations(patientId, hospitalId, null, 5)).isEmpty();
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(), eq(Map.of()), anyString());
    }

    @Test
    void aCriticalAlertThatClosesTheImmediateWindowLandsInEnhancedMonitoring() {
        // QA D104: after a critical alert the checks fell back to every 480 min.
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateObservationTarget(2);
        plan.setImmediateObservationsCompleted(1);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .stabilizationConfirmed(Boolean.TRUE)
            .temperatureCelsius(38.5)
            .foulLochiaOdor(Boolean.TRUE)
            .educationCompleted(Boolean.TRUE)
            .build();

        service.recordObservation(patientId, request, recorderId);

        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
        assertTrue(plan.getShiftFrequencyMinutes() < PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES);
    }

    /* ── QA D268 (round 5) ────────────────────────────────────────────── */

    @Test
    void aFutureDatedObservationIsRefusedAndTheScheduleIsUntouched_D268() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        LocalDateTime nextDue = LocalDateTime.now().plusMinutes(10);
        plan.setNextDueAt(nextDue);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().plusHours(24))
            .fundusTone(PostpartumFundusTone.FIRM)
            .build();

        assertThatThrownBy(() -> service.recordObservation(patientId, request, recorderId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("maternity.postpartum.observationTime.future");
        verify(observationRepository, never()).save(any());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(nextDue, plan.getNextDueAt());
    }

    @Test
    void afterAHaemorrhageTheFifteenMinuteChecksOutliveTheTwoHourWindow_D268() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusHours(3));
        plan.setImmediateObservationsCompleted(2);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));
        when(observationRepository.existsByCarePlan_IdAndHemorrhageProtocolActivatedTrue(plan.getId()))
            .thenReturn(true);

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .build();

        service.recordObservation(patientId, request, recorderId);

        assertFalse(plan.isImmediateWindowCompleted());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(observationTime.plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES), plan.getNextDueAt());
    }

    @Test
    void aSystolicOf900IsRefusedByValidation_D277() {
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId).systolicBpMmHg(900).build();
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("systolicBpMmHg");
        }
    }

    /* ── QA D215 / D216 (round 4) ─────────────────────────────────────── */

    @Test
    void anObservationAfterANewDeliveryStartsAFreshPlanInsteadOfReusingTheOldOne() {
        LocalDateTime firstBirth = LocalDateTime.now().minusDays(2);
        LocalDateTime secondBirth = LocalDateTime.now().minusMinutes(30);
        PostpartumCarePlan oldPlan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .deliveryOccurredAt(firstBirth)
            .build();
        oldPlan.setId(UUID.randomUUID());
        oldPlan.setActive(true);
        oldPlan.setImmediateWindowCompleted(true);
        oldPlan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        oldPlan.setShiftFrequencyMinutes(240);
        oldPlan.setOverdueSince(LocalDateTime.now().minusHours(5));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(oldPlan));
        com.example.hms.model.labor.DeliveryRecord latest = com.example.hms.model.labor.DeliveryRecord.builder()
            .birthDateTime(secondBirth).build();
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(latest));

        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now())
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertFalse(oldPlan.isActive(), "the earlier delivery's plan must be closed");
        ArgumentCaptor<PostpartumCarePlan> saved = ArgumentCaptor.forClass(PostpartumCarePlan.class);
        verify(carePlanRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        PostpartumCarePlan fresh = saved.getAllValues().stream().filter(p -> p != oldPlan).findFirst().orElseThrow();
        assertEquals(secondBirth, fresh.getDeliveryOccurredAt());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, fresh.getActivePhase());
        assertFalse(fresh.isImmediateWindowCompleted());
        assertFalse(response.getSchedule().isOverdue(), "a check due in the future is not overdue");
    }

    @Test
    void urgentPostpartumNotificationsAreFrenchWithATranslatedType() {
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .excessiveBleeding(true)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(message.capture(), anyString());
        assertTrue(message.getValue().startsWith("Alerte post-partum (hémorragie)"), message.getValue());
        assertFalse(message.getValue().contains("HEMORRHAGE"), message.getValue());
        assertEquals("Évaluation des saignements", response.getAlerts().get(0).getTriggeredBy());
    }
}
