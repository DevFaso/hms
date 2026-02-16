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
            new PostpartumObservationMapper()
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
        lenient().when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
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
        assertTrue(response.getAlerts().get(0).getMessage().toLowerCase().contains("hemorrhage"));

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
}
