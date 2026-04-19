package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.mapper.FamilyHistoryMapper;
import com.example.hms.mapper.PatientSurgicalHistoryMapper;
import com.example.hms.mapper.QuestionnaireMapper;
import com.example.hms.mapper.SocialHistoryMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.model.PatientFamilyHistory;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO;
import com.example.hms.payload.dto.portal.PatientDiagnosisSummaryDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.FamilyHistoryRepository;
import com.example.hms.repository.PatientDiagnosisRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.SocialHistoryRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.PatientConsentService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientPrimaryCareService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TreatmentPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for patient portal medical/surgical/family/social history methods.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplHistoryTest {

    // ── All @Mock fields required by PatientPortalServiceImpl constructor ──
    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientLabResultService labResultService;
    @Mock private PatientMedicationService medicationService;
    @Mock private PatientVitalSignService vitalSignService;
    @Mock private PatientConsentService consentService;
    @Mock private ImmunizationService immunizationService;
    @Mock private BillingInvoiceService billingInvoiceService;
    @Mock private EncounterService encounterService;
    @Mock private PrescriptionService prescriptionService;
    @Mock private AppointmentService appointmentService;
    @Mock private ConsultationService consultationService;
    @Mock private TreatmentPlanService treatmentPlanService;
    @Mock private GeneralReferralService referralService;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private DischargeSummaryService dischargeSummaryService;
    @Mock private PatientPrimaryCareService primaryCareService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private com.example.hms.repository.HospitalRepository hospitalRepository;
    @Mock private com.example.hms.repository.DepartmentRepository departmentRepository;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private com.example.hms.service.StaffAvailabilityService staffAvailabilityService;
    @Mock private com.example.hms.repository.PatientProxyRepository patientProxyRepository;
    @Mock private com.example.hms.repository.UserRepository userRepository;
    @Mock private com.example.hms.service.NotificationService notificationService;
    @Mock private com.example.hms.service.EmailService emailService;
    @Mock private com.example.hms.repository.QuestionnaireRepository questionnaireRepository;
    @Mock private com.example.hms.repository.QuestionnaireResponseRepository questionnaireResponseRepository;
    @Mock private QuestionnaireMapper questionnaireMapper;

    // History-specific mocks
    @Mock private PatientDiagnosisRepository patientDiagnosisRepository;
    @Mock private PatientSurgicalHistoryRepository surgicalHistoryRepository;
    @Mock private FamilyHistoryRepository familyHistoryRepository;
    @Mock private SocialHistoryRepository socialHistoryRepository;
    @Mock private PatientSurgicalHistoryMapper surgicalHistoryMapper;
    @Mock private FamilyHistoryMapper familyHistoryMapper;
    @Mock private SocialHistoryMapper socialHistoryMapper;

    @InjectMocks private PatientPortalServiceImpl service;
    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);
        user.setUsername("patient.john");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Smith");
        patient.setUser(user);
    }

    private void stubPatientResolution() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    // ══════════════════════════════════════════════════════════════════════
    // getMyMedicalHistory
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyMedicalHistory")
    class GetMyMedicalHistory {

        @Test
        @DisplayName("should return mapped diagnosis list")
        void returnsMappedDiagnoses() {
            stubPatientResolution();

            Staff doctor = new Staff();
            User doctorUser = new User();
            doctorUser.setFirstName("Dr.");
            doctorUser.setLastName("House");
            doctor.setUser(doctorUser);

            PatientDiagnosis d = new PatientDiagnosis();
            d.setId(UUID.randomUUID());
            d.setDescription("Hypertension");
            d.setIcdCode("I10");
            d.setStatus("ACTIVE");
            d.setDiagnosedAt(OffsetDateTime.now());
            d.setDiagnosedBy(doctor);

            when(patientDiagnosisRepository.findByPatient_IdOrderByDiagnosedAtDesc(patientId))
                    .thenReturn(List.of(d));

            List<PatientDiagnosisSummaryDTO> result = service.getMyMedicalHistory(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDescription()).isEqualTo("Hypertension");
            assertThat(result.get(0).getIcdCode()).isEqualTo("I10");
            assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");
            assertThat(result.get(0).getDiagnosedByName()).isEqualTo("Dr. House");
            verify(patientDiagnosisRepository).findByPatient_IdOrderByDiagnosedAtDesc(patientId);
        }

        @Test
        @DisplayName("should return empty list when no diagnoses")
        void returnsEmptyWhenNoDiagnoses() {
            stubPatientResolution();
            when(patientDiagnosisRepository.findByPatient_IdOrderByDiagnosedAtDesc(patientId))
                    .thenReturn(Collections.emptyList());

            List<PatientDiagnosisSummaryDTO> result = service.getMyMedicalHistory(auth);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle null diagnosedBy gracefully")
        void handlesNullDiagnosedBy() {
            stubPatientResolution();

            PatientDiagnosis d = new PatientDiagnosis();
            d.setId(UUID.randomUUID());
            d.setDescription("Asthma");
            d.setIcdCode("J45");
            d.setStatus("ACTIVE");
            d.setDiagnosedAt(OffsetDateTime.now());
            d.setDiagnosedBy(null);

            when(patientDiagnosisRepository.findByPatient_IdOrderByDiagnosedAtDesc(patientId))
                    .thenReturn(List.of(d));

            List<PatientDiagnosisSummaryDTO> result = service.getMyMedicalHistory(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDiagnosedByName()).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // getMySurgicalHistory
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMySurgicalHistory")
    class GetMySurgicalHistory {

        @Test
        @DisplayName("should return mapped surgical history list")
        void returnsMappedSurgicalHistory() {
            stubPatientResolution();

            PatientSurgicalHistory sh = new PatientSurgicalHistory();
            sh.setId(UUID.randomUUID());
            sh.setProcedureDisplay("Appendectomy");

            PatientSurgicalHistoryResponseDTO dto = PatientSurgicalHistoryResponseDTO.builder()
                    .id(sh.getId())
                    .procedureDisplay("Appendectomy")
                    .build();

            when(surgicalHistoryRepository.findByPatient_Id(patientId))
                    .thenReturn(List.of(sh));
            when(surgicalHistoryMapper.toResponseDto(sh)).thenReturn(dto);

            List<PatientSurgicalHistoryResponseDTO> result = service.getMySurgicalHistory(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProcedureDisplay()).isEqualTo("Appendectomy");
            verify(surgicalHistoryRepository).findByPatient_Id(patientId);
            verify(surgicalHistoryMapper).toResponseDto(sh);
        }

        @Test
        @DisplayName("should return empty list when no surgical history")
        void returnsEmptyWhenNoSurgicalHistory() {
            stubPatientResolution();
            when(surgicalHistoryRepository.findByPatient_Id(patientId))
                    .thenReturn(Collections.emptyList());

            List<PatientSurgicalHistoryResponseDTO> result = service.getMySurgicalHistory(auth);

            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // getMyFamilyHistory
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyFamilyHistory")
    class GetMyFamilyHistory {

        @Test
        @DisplayName("should return mapped family history list")
        void returnsMappedFamilyHistory() {
            stubPatientResolution();

            PatientFamilyHistory fh = new PatientFamilyHistory();
            fh.setId(UUID.randomUUID());
            fh.setRelationship("Father");
            fh.setConditionDisplay("Diabetes");

            FamilyHistoryResponseDTO dto = FamilyHistoryResponseDTO.builder()
                    .id(fh.getId())
                    .relationship("Father")
                    .conditionDisplay("Diabetes")
                    .build();

            when(familyHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId))
                    .thenReturn(List.of(fh));
            when(familyHistoryMapper.toResponseDTO(fh)).thenReturn(dto);

            List<FamilyHistoryResponseDTO> result = service.getMyFamilyHistory(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRelationship()).isEqualTo("Father");
            assertThat(result.get(0).getConditionDisplay()).isEqualTo("Diabetes");
            verify(familyHistoryRepository).findByPatient_IdOrderByRecordedDateDesc(patientId);
            verify(familyHistoryMapper).toResponseDTO(fh);
        }

        @Test
        @DisplayName("should return empty list when no family history")
        void returnsEmptyWhenNoFamilyHistory() {
            stubPatientResolution();
            when(familyHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId))
                    .thenReturn(Collections.emptyList());

            List<FamilyHistoryResponseDTO> result = service.getMyFamilyHistory(auth);

            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // getMySocialHistory
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMySocialHistory")
    class GetMySocialHistory {

        @Test
        @DisplayName("should return mapped social history when present")
        void returnsMappedSocialHistory() {
            stubPatientResolution();

            PatientSocialHistory sh = new PatientSocialHistory();
            sh.setId(UUID.randomUUID());
            sh.setTobaccoUse(false);
            sh.setAlcoholUse(true);
            sh.setAlcoholFrequency("Social");

            SocialHistoryResponseDTO dto = SocialHistoryResponseDTO.builder()
                    .id(sh.getId())
                    .tobaccoUse(false)
                    .alcoholUse(true)
                    .alcoholFrequency("Social")
                    .build();

            when(socialHistoryRepository.findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patientId))
                    .thenReturn(Optional.of(sh));
            when(socialHistoryMapper.toResponseDTO(sh)).thenReturn(dto);

            SocialHistoryResponseDTO result = service.getMySocialHistory(auth);

            assertThat(result).isNotNull();
            assertThat(result.getTobaccoUse()).isFalse();
            assertThat(result.getAlcoholUse()).isTrue();
            assertThat(result.getAlcoholFrequency()).isEqualTo("Social");
            verify(socialHistoryRepository).findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patientId);
            verify(socialHistoryMapper).toResponseDTO(sh);
        }

        @Test
        @DisplayName("should return null when no social history")
        void returnsNullWhenNoSocialHistory() {
            stubPatientResolution();
            when(socialHistoryRepository.findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patientId))
                    .thenReturn(Optional.empty());

            SocialHistoryResponseDTO result = service.getMySocialHistory(auth);

            assertThat(result).isNull();
        }
    }
}
