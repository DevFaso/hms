package com.example.hms.service.impl;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.NewbornAssessmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewbornAssessmentServiceImplTest {

    @Mock
    private NewbornAssessmentRepository assessmentRepository;
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

    private NewbornAssessmentServiceImpl service;
    private UUID patientId;
    private UUID hospitalId;
    private UUID recorderId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new NewbornAssessmentServiceImpl(
            assessmentRepository,
            patientRepository,
            registrationRepository,
            hospitalRepository,
            staffRepository,
            userRepository,
            notificationService,
            new NewbornAssessmentMapper()
        );

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        recorderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Test");
        patient.setLastName("Baby");
        patient.setDateOfBirth(LocalDate.now());
        patient.setHospitalId(hospitalId);

        hospital = Hospital.builder()
            .name("Demo")
            .code("DEMO")
            .build();
        hospital.setId(hospitalId);

        User recorder = User.builder()
            .username("nurse.neo")
            .email("nurse.neo@example.com")
            .passwordHash("hash")
            .build();
        recorder.setId(recorderId);

        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(notificationService.createNotification(anyString(), anyString())).thenReturn(null);
        lenient().when(assessmentRepository.save(any(NewbornAssessment.class))).thenAnswer(invocation -> {
            NewbornAssessment assessment = invocation.getArgument(0);
            assessment.setId(UUID.randomUUID());
            return assessment;
        });
    }

    @Test
    void recordAssessmentWithLowApgarTriggersUrgentAlert() {
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .apgarOneMinute(5)
            .apgarFiveMinute(6)
            .temperatureCelsius(36.8)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertNotNull(response);
        assertEquals(2, response.getAlerts().size(), "Expected urgent alerts for low Apgar scores");
        assertEquals(NewbornAlertType.APGAR, response.getAlerts().get(0).getType());
        assertEquals(NewbornAlertSeverity.URGENT, response.getAlerts().get(0).getSeverity());
        assertEquals(NewbornAlertSeverity.URGENT, response.getAlerts().get(1).getSeverity());
        assertTrue(response.getFollowUpActions().contains(NewbornFollowUpAction.NICU_CONSULT));
        verify(notificationService, times(2)).createNotification(anyString(), anyString());

        ArgumentCaptor<NewbornAssessment> assessmentCaptor = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(assessmentCaptor.capture());
        assertTrue(assessmentCaptor.getValue().isEscalationRecommended());
    }

    @Test
    void recordAssessmentStableDoesNotTriggerAlert() {
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .temperatureCelsius(36.9)
            .heartRateBpm(140)
            .respirationsPerMin(48)
            .oxygenSaturationPercent(95)
            .glucoseMgDl(60)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(response.getAlerts().isEmpty(), "No alerts expected for stable assessment");
        verify(notificationService, never()).createNotification(anyString(), anyString());
    }

    @Test
    void recordAssessmentWithNullRequestThrowsBusinessException() {
        assertThrows(BusinessException.class, () -> service.recordAssessment(patientId, null, recorderId));
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void searchAssessmentsReturnsPagedResults() {
        when(assessmentRepository.findWithinRange(any(), any(), any(), any(), any())).thenReturn(List.of());
        List<NewbornAssessmentResponseDTO> results = service.searchAssessments(patientId, hospitalId, null, null, 0, 25);
        assertNotNull(results);
        verify(assessmentRepository).findWithinRange(any(), any(), any(), any(), any());
    }
}
