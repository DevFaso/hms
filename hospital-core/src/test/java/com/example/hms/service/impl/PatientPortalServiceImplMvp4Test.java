package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.mapper.QuestionnaireMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Questionnaire;
import com.example.hms.model.QuestionnaireResponse;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.PreCheckInRequestDTO;
import com.example.hms.payload.dto.portal.PreCheckInResponseDTO;
import com.example.hms.payload.dto.portal.QuestionnaireDTO;
import com.example.hms.payload.dto.portal.QuestionnaireSubmissionDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.QuestionnaireRepository;
import com.example.hms.repository.QuestionnaireResponseRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
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
import com.example.hms.service.StaffAvailabilityService;
import com.example.hms.service.TreatmentPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MVP 4 – Pre-Visit Questionnaires & Pre-Check-In.
 * Tests: getQuestionnairesForAppointment, submitPreCheckIn.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplMvp4Test {

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
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private StaffAvailabilityService staffAvailabilityService;
    @Mock private com.example.hms.repository.PatientProxyRepository patientProxyRepository;
    @Mock private com.example.hms.repository.UserRepository userRepository;
    @Mock private com.example.hms.service.NotificationService notificationService;
    @Mock private com.example.hms.service.EmailService emailService;
    @Mock private QuestionnaireRepository questionnaireRepository;
    @Mock private QuestionnaireResponseRepository questionnaireResponseRepository;
    @Mock private QuestionnaireMapper questionnaireMapper;

    @InjectMocks private PatientPortalServiceImpl service;
    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private Patient patient;
    private Hospital hospital;
    private Department department;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);
        user.setUsername("patient.jane");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setUser(user);

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("General Hospital");

        department = new Department();
        department.setId(UUID.randomUUID());
        department.setName("Cardiology");
        department.setHospital(hospital);
    }

    private void stubPatientResolution() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    private Appointment buildAppointment(UUID apptId, AppointmentStatus status, int daysFromNow) {
        Appointment appt = new Appointment();
        appt.setId(apptId);
        appt.setStatus(status);
        appt.setPatient(patient);
        appt.setHospital(hospital);
        appt.setDepartment(department);
        appt.setAppointmentDate(LocalDate.now().plusDays(daysFromNow));
        appt.setStartTime(LocalTime.of(9, 0));
        appt.setEndTime(LocalTime.of(9, 30));
        appt.setPreCheckedIn(false);
        return appt;
    }

    private Questionnaire buildQuestionnaire(UUID id, String title, boolean hospitalWide) {
        Questionnaire q = new Questionnaire();
        q.setId(id);
        q.setTitle(title);
        q.setDescription("Desc for " + title);
        q.setQuestions("[{\"id\":\"q1\",\"text\":\"Have allergies?\",\"type\":\"YES_NO\"}]");
        q.setVersion(1);
        q.setActive(true);
        q.setHospital(hospital);
        q.setDepartment(hospitalWide ? null : department);
        return q;
    }

    // ══════════════════════════════════════════════════════════════════════
    // getQuestionnairesForAppointment
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getQuestionnairesForAppointment")
    class GetQuestionnairesTests {

        @Test
        @DisplayName("returns department-specific + hospital-wide questionnaires")
        void happyPath_withDepartment() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            UUID q1Id = UUID.randomUUID();
            UUID q2Id = UUID.randomUUID();
            Questionnaire deptQ = buildQuestionnaire(q1Id, "Cardio Screen", false);
            Questionnaire hosQ = buildQuestionnaire(q2Id, "General Intake", true);

            when(questionnaireRepository.findByHospital_IdAndDepartment_IdAndActiveTrue(
                    hospital.getId(), department.getId()))
                    .thenReturn(List.of(deptQ));
            when(questionnaireRepository.findByHospital_IdAndActiveTrue(hospital.getId()))
                    .thenReturn(List.of(hosQ));

            QuestionnaireDTO dto1 = QuestionnaireDTO.builder().id(q1Id).title("Cardio Screen").build();
            QuestionnaireDTO dto2 = QuestionnaireDTO.builder().id(q2Id).title("General Intake").build();
            when(questionnaireMapper.toDto(deptQ)).thenReturn(dto1);
            when(questionnaireMapper.toDto(hosQ)).thenReturn(dto2);

            List<QuestionnaireDTO> result = service.getQuestionnairesForAppointment(auth, apptId);
            assertThat(result).hasSize(2);
            assertThat(result).extracting(QuestionnaireDTO::getTitle)
                    .containsExactlyInAnyOrder("Cardio Screen", "General Intake");
        }

        @Test
        @DisplayName("returns only hospital-wide when department is null")
        void noDepartment() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.CONFIRMED, 2);
            appt.setDepartment(null);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            UUID qId = UUID.randomUUID();
            Questionnaire hosQ = buildQuestionnaire(qId, "Hospital Intake", true);
            when(questionnaireRepository.findByHospital_IdAndActiveTrue(hospital.getId()))
                    .thenReturn(List.of(hosQ));
            when(questionnaireMapper.toDto(hosQ))
                    .thenReturn(QuestionnaireDTO.builder().id(qId).title("Hospital Intake").build());

            List<QuestionnaireDTO> result = service.getQuestionnairesForAppointment(auth, apptId);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("throws when appointment not found")
        void appointmentNotFound() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getQuestionnairesForAppointment(auth, apptId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when patient does not own appointment")
        void ownershipViolation() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());
            appt.setPatient(otherPatient);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            assertThatThrownBy(() -> service.getQuestionnairesForAppointment(auth, apptId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("access");
        }

        @Test
        @DisplayName("deduplicates questionnaires from dept and hospital-wide lists")
        void deduplicates() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            UUID qId = UUID.randomUUID();
            Questionnaire shared = buildQuestionnaire(qId, "Shared Q", false);
            Questionnaire sharedCopy = buildQuestionnaire(qId, "Shared Q", true);

            when(questionnaireRepository.findByHospital_IdAndDepartment_IdAndActiveTrue(
                    hospital.getId(), department.getId()))
                    .thenReturn(List.of(shared));
            when(questionnaireRepository.findByHospital_IdAndActiveTrue(hospital.getId()))
                    .thenReturn(List.of(sharedCopy));

            QuestionnaireDTO dto = QuestionnaireDTO.builder().id(qId).title("Shared Q").build();
            when(questionnaireMapper.toDto(shared)).thenReturn(dto);

            List<QuestionnaireDTO> result = service.getQuestionnairesForAppointment(auth, apptId);
            assertThat(result).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // submitPreCheckIn
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("submitPreCheckIn")
    class SubmitPreCheckInTests {

        private PreCheckInRequestDTO buildDto(UUID apptId) {
            return PreCheckInRequestDTO.builder()
                    .appointmentId(apptId)
                    .consentAcknowledged(true)
                    .build();
        }

        @Test
        @DisplayName("happy path — marks pre-checked-in, returns response")
        void happyPath() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PreCheckInRequestDTO dto = buildDto(apptId);
            PreCheckInResponseDTO result = service.submitPreCheckIn(auth, dto);

            assertThat(result.getPreCheckedIn()).isTrue();
            assertThat(result.getAppointmentId()).isEqualTo(apptId);
            assertThat(result.getPreCheckinTimestamp()).isNotNull();
            assertThat(result.getQuestionnaireResponsesSubmitted()).isZero();
            assertThat(result.getDemographicsUpdated()).isFalse();

            verify(appointmentRepository).save(appt);
            assertThat(appt.getPreCheckedIn()).isTrue();
        }

        @Test
        @DisplayName("saves questionnaire responses and counts them")
        void withQuestionnaireResponses() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.CONFIRMED, 2);
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UUID qId = UUID.randomUUID();
            Questionnaire questionnaire = buildQuestionnaire(qId, "Intake", false);
            when(questionnaireRepository.findById(qId)).thenReturn(Optional.of(questionnaire));
            when(questionnaireResponseRepository.existsByQuestionnaire_IdAndAppointment_Id(qId, apptId))
                    .thenReturn(false);
            when(questionnaireResponseRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            QuestionnaireSubmissionDTO sub = QuestionnaireSubmissionDTO.builder()
                    .questionnaireId(qId)
                    .responses("{\"q1\":true}")
                    .build();

            PreCheckInRequestDTO dto = PreCheckInRequestDTO.builder()
                    .appointmentId(apptId)
                    .questionnaireResponses(List.of(sub))
                    .consentAcknowledged(true)
                    .build();

            PreCheckInResponseDTO result = service.submitPreCheckIn(auth, dto);
            assertThat(result.getQuestionnaireResponsesSubmitted()).isEqualTo(1);

            ArgumentCaptor<QuestionnaireResponse> captor = ArgumentCaptor.forClass(QuestionnaireResponse.class);
            verify(questionnaireResponseRepository).save(captor.capture());
            assertThat(captor.getValue().getResponses()).isEqualTo("{\"q1\":true}");
        }

        @Test
        @DisplayName("skips duplicate questionnaire response (idempotent)")
        void idempotentQuestionnaire() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UUID qId = UUID.randomUUID();
            Questionnaire questionnaire = buildQuestionnaire(qId, "Intake", false);
            when(questionnaireRepository.findById(qId)).thenReturn(Optional.of(questionnaire));
            when(questionnaireResponseRepository.existsByQuestionnaire_IdAndAppointment_Id(qId, apptId))
                    .thenReturn(true); // already exists

            QuestionnaireSubmissionDTO sub = QuestionnaireSubmissionDTO.builder()
                    .questionnaireId(qId)
                    .responses("{\"q1\":true}")
                    .build();

            PreCheckInRequestDTO dto = PreCheckInRequestDTO.builder()
                    .appointmentId(apptId)
                    .questionnaireResponses(List.of(sub))
                    .consentAcknowledged(true)
                    .build();

            PreCheckInResponseDTO result = service.submitPreCheckIn(auth, dto);
            assertThat(result.getQuestionnaireResponsesSubmitted()).isZero();
            verify(questionnaireResponseRepository, never()).save(any());
        }

        @Test
        @DisplayName("updates demographics and sets flag")
        void withDemographicsUpdate() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PreCheckInRequestDTO dto = PreCheckInRequestDTO.builder()
                    .appointmentId(apptId)
                    .phoneNumber("555-1234")
                    .email("jane@example.com")
                    .consentAcknowledged(true)
                    .build();

            PreCheckInResponseDTO result = service.submitPreCheckIn(auth, dto);
            assertThat(result.getDemographicsUpdated()).isTrue();
            assertThat(patient.getPhoneNumberPrimary()).isEqualTo("555-1234");
            assertThat(patient.getEmail()).isEqualTo("jane@example.com");
            verify(patientRepository).save(patient);
        }

        @Test
        @DisplayName("throws when appointment not found")
        void appointmentNotFound() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when patient does not own appointment")
        void ownershipViolation() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());
            appt.setPatient(otherPatient);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("access");
        }

        @Test
        @DisplayName("throws for CANCELLED appointment")
        void invalidStatus_cancelled() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.CANCELLED, 3);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SCHEDULED or CONFIRMED");
        }

        @Test
        @DisplayName("throws for COMPLETED appointment")
        void invalidStatus_completed() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.COMPLETED, 3);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("throws when appointment is more than 7 days away")
        void tooEarly() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 10);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1–7 days");
        }

        @Test
        @DisplayName("throws when appointment is in the past")
        void inThePast() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, -1);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            PreCheckInRequestDTO dto = buildDto(apptId);
            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1–7 days");
        }

        @Test
        @DisplayName("questionnaire not found throws ResourceNotFoundException")
        void questionnaireNotFound() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appt = buildAppointment(apptId, AppointmentStatus.SCHEDULED, 3);
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appt));

            UUID unknownQId = UUID.randomUUID();
            when(questionnaireRepository.findById(unknownQId)).thenReturn(Optional.empty());

            QuestionnaireSubmissionDTO sub = QuestionnaireSubmissionDTO.builder()
                    .questionnaireId(unknownQId)
                    .responses("{}")
                    .build();

            PreCheckInRequestDTO dto = PreCheckInRequestDTO.builder()
                    .appointmentId(apptId)
                    .questionnaireResponses(List.of(sub))
                    .consentAcknowledged(true)
                    .build();

            assertThatThrownBy(() -> service.submitPreCheckIn(auth, dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
