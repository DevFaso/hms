package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.HealthMaintenanceReminderStatus;
import com.example.hms.enums.HealthMaintenanceReminderType;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientReportedOutcomeType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.QuestionnaireStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.mapper.PharmacyFillMapper;
import com.example.hms.mapper.QuestionnaireMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.HealthMaintenanceReminder;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.PatientReportedOutcome;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.TreatmentProgressEntry;
import com.example.hms.model.User;
import com.example.hms.model.medication.PharmacyFill;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.model.questionnaire.PreVisitQuestionnaire;
import com.example.hms.model.questionnaire.QuestionnaireResponse;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import com.example.hms.payload.dto.DepartmentMinimalDTO;
import com.example.hms.payload.dto.DepartmentWithStaffDTO;
import com.example.hms.payload.dto.EncounterNoteResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.education.EducationResourceResponseDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.lab.LabResultTrendDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.payload.dto.portal.PortalAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalOutcomeRequestDTO;
import com.example.hms.payload.dto.portal.PortalProgressEntryRequestDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.questionnaire.PreVisitQuestionnaireDTO;
import com.example.hms.payload.dto.questionnaire.QuestionnaireResponseDTO;
import com.example.hms.payload.dto.questionnaire.QuestionnaireResponseSubmitDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.HealthMaintenanceReminderRepository;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientReportedOutcomeRepository;
import com.example.hms.repository.PharmacyFillRepository;
import com.example.hms.repository.PreVisitQuestionnaireRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.QuestionnaireResponseRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.TreatmentProgressEntryRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AdmissionService;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DepartmentService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.ImmunizationCertificatePdfService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.MedicationHistoryService;
import com.example.hms.service.PatientConsentService;
import com.example.hms.service.PatientEducationService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientPrimaryCareService;
import com.example.hms.service.PatientRecordSharingService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.ProcedureOrderService;
import com.example.hms.service.TreatmentPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976")
class PatientPortalServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientProxyRepository patientProxyRepository;
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
    @Mock private UserRepository userRepository;
    @Mock private NotificationPreferenceRepository notificationPreferenceRepository;
    @Mock private LabOrderService labOrderService;
    @Mock private ImagingOrderService imagingOrderService;
    @Mock private PharmacyFillRepository pharmacyFillRepository;
    @Mock private PharmacyFillMapper pharmacyFillMapper;
    @Mock private ProcedureOrderService procedureOrderService;
    @Mock private AdmissionService admissionService;
    @Mock private PatientEducationService patientEducationService;
    @Mock private PatientRecordSharingService recordSharingService;
    @Mock private DepartmentService departmentService;
    @Mock private PreVisitQuestionnaireRepository preVisitQuestionnaireRepository;
    @Mock private QuestionnaireResponseRepository questionnaireResponseRepository;
    @Mock private QuestionnaireMapper questionnaireMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private EncounterNoteRepository encounterNoteRepository;
    @Mock private EncounterMapper encounterMapper;
    @Mock private ImmunizationCertificatePdfService immunizationCertificatePdfService;
    @Mock private HealthMaintenanceReminderRepository healthMaintenanceReminderRepository;
    @Mock private TreatmentProgressEntryRepository treatmentProgressEntryRepository;
    @Mock private PatientReportedOutcomeRepository patientReportedOutcomeRepository;
    @Mock private Authentication auth;

    @InjectMocks private PatientPortalServiceImpl service;

    private UUID userId;
    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        User user = new User();
        user.setId(userId);
        patient.setUser(user);
        patient.setHospitalId(hospitalId);
        patient.setFirstName("John");
        patient.setLastName("Doe");
    }

    private void stubAuth() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    // ── Identity Resolution ─────────────────────────────────────────────

    @Test void resolvePatientId_success() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
        assertThat(service.resolvePatientId(auth)).isEqualTo(patientId);
    }

    @Test void resolvePatientId_authFails() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolvePatientId(auth))
                .isInstanceOf(BusinessException.class);
    }

    @Test void resolvePatientId_noPatient() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolvePatientId(auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Feature 4: Lab Orders ───────────────────────────────────────────

    @Test void getMyLabOrders_delegatesToService() {
        stubAuth();
        List<LabOrderResponseDTO> expected = List.of(new LabOrderResponseDTO());
        when(labOrderService.getLabOrdersByPatientId(patientId, Locale.US)).thenReturn(expected);
        assertThat(service.getMyLabOrders(auth, Locale.US)).isEqualTo(expected);
    }

    // ── Feature 5: Imaging Orders ───────────────────────────────────────

    @Test void getMyImagingOrders_delegatesWithNullStatus() {
        stubAuth();
        List<ImagingOrderResponseDTO> expected = List.of(new ImagingOrderResponseDTO());
        when(imagingOrderService.getOrdersByPatient(patientId, null)).thenReturn(expected);
        assertThat(service.getMyImagingOrders(auth)).isEqualTo(expected);
    }

    // ── Feature 6: Pharmacy Fills ───────────────────────────────────────

    @Test void getMyPharmacyFills_returnsMappedList() {
        stubAuth();
        var fill = new PharmacyFill();
        when(pharmacyFillRepository.findByPatient_IdOrderByFillDateDesc(patientId))
                .thenReturn(List.of(fill));
        PharmacyFillResponseDTO dto = new PharmacyFillResponseDTO();
        when(pharmacyFillMapper.toResponseDTO(fill)).thenReturn(dto);
        assertThat(service.getMyPharmacyFills(auth, Locale.US)).containsExactly(dto);
    }

    // ── Feature 7: Procedure Orders ─────────────────────────────────────

    @Test void getMyProcedureOrders_delegates() {
        stubAuth();
        List<ProcedureOrderResponseDTO> expected = List.of(new ProcedureOrderResponseDTO());
        when(procedureOrderService.getProcedureOrdersForPatient(patientId)).thenReturn(expected);
        assertThat(service.getMyProcedureOrders(auth)).isEqualTo(expected);
    }

    // ── Feature 8: Admissions ───────────────────────────────────────────

    @Test void getMyAdmissions_delegates() {
        stubAuth();
        List<AdmissionResponseDTO> expected = List.of(new AdmissionResponseDTO());
        when(admissionService.getAdmissionsByPatient(patientId)).thenReturn(expected);
        assertThat(service.getMyAdmissions(auth)).isEqualTo(expected);
    }

    @Test void getMyCurrentAdmission_delegates() {
        stubAuth();
        AdmissionResponseDTO expected = new AdmissionResponseDTO();
        when(admissionService.getCurrentAdmissionForPatient(patientId)).thenReturn(expected);
        assertThat(service.getMyCurrentAdmission(auth)).isEqualTo(expected);
    }

    // ── Feature 9: Education Progress ───────────────────────────────────

    @Test void getMyEducationProgress_delegates() {
        stubAuth();
        List<PatientEducationProgressResponseDTO> expected = List.of(new PatientEducationProgressResponseDTO());
        when(patientEducationService.getPatientProgress(patientId)).thenReturn(expected);
        assertThat(service.getMyEducationProgress(auth)).isEqualTo(expected);
    }

    @Test void getMyInProgressEducation_delegates() {
        stubAuth();
        List<PatientEducationProgressResponseDTO> expected = List.of(new PatientEducationProgressResponseDTO());
        when(patientEducationService.getInProgressResources(patientId)).thenReturn(expected);
        assertThat(service.getMyInProgressEducation(auth)).isEqualTo(expected);
    }

    @Test void getMyCompletedEducation_delegates() {
        stubAuth();
        List<PatientEducationProgressResponseDTO> expected = List.of(new PatientEducationProgressResponseDTO());
        when(patientEducationService.getCompletedResources(patientId)).thenReturn(expected);
        assertThat(service.getMyCompletedEducation(auth)).isEqualTo(expected);
    }

    // ── Feature 10: Browse Education Resources ──────────────────────────

    @Test void getMyEducationResources_withHospital() {
        stubAuth();
        List<EducationResourceResponseDTO> expected = List.of(new EducationResourceResponseDTO());
        when(patientEducationService.getAllResources(hospitalId)).thenReturn(expected);
        assertThat(service.getMyEducationResources(auth)).isEqualTo(expected);
    }

    @Test void getMyEducationResources_nullHospital_returnsEmpty() {
        patient.setHospitalId(null);
        stubAuth();
        when(registrationRepository.findByPatientId(patientId)).thenReturn(Collections.emptyList());
        assertThat(service.getMyEducationResources(auth)).isEmpty();
    }

    @Test void searchMyEducationResources_delegates() {
        stubAuth();
        List<EducationResourceResponseDTO> expected = List.of(new EducationResourceResponseDTO());
        when(patientEducationService.searchResources("test", hospitalId)).thenReturn(expected);
        assertThat(service.searchMyEducationResources(auth, "test")).isEqualTo(expected);
    }

    @Test void searchMyEducationResources_nullHospital_returnsEmpty() {
        patient.setHospitalId(null);
        stubAuth();
        when(registrationRepository.findByPatientId(patientId)).thenReturn(Collections.emptyList());
        assertThat(service.searchMyEducationResources(auth, "query")).isEmpty();
    }

    @Test void getMyEducationResourcesByCategory_delegates() {
        stubAuth();
        List<EducationResourceResponseDTO> expected = List.of(new EducationResourceResponseDTO());
        when(patientEducationService.getResourcesByCategory(EducationCategory.NUTRITION, hospitalId))
                .thenReturn(expected);
        assertThat(service.getMyEducationResourcesByCategory(auth, EducationCategory.NUTRITION))
                .isEqualTo(expected);
    }

    @Test void getMyEducationResourcesByCategory_nullHospital() {
        patient.setHospitalId(null);
        stubAuth();
        when(registrationRepository.findByPatientId(patientId)).thenReturn(Collections.emptyList());
        assertThat(service.getMyEducationResourcesByCategory(auth, EducationCategory.NUTRITION)).isEmpty();
    }

    // ── Feature 11: Medical Records Download ────────────────────────────

    @Test void downloadMyRecord_delegates() {
        stubAuth();
        byte[] expected = new byte[]{1, 2, 3};
        when(recordSharingService.exportSelfRecord(patientId, "pdf")).thenReturn(expected);
        assertThat(service.downloadMyRecord(auth, "pdf")).isEqualTo(expected);
    }

    // ── Feature 12: Lab Result Trends ───────────────────────────────────

    @Test void getMyLabResultTrends_delegates() {
        stubAuth();
        List<LabResultTrendDTO> expected = List.of(new LabResultTrendDTO());
        when(labResultService.getLabResultTrends(patientId)).thenReturn(expected);
        assertThat(service.getMyLabResultTrends(auth)).isEqualTo(expected);
    }

    // ── Feature 13: Online Check-In ─────────────────────────────────────

    @Test void checkInMyAppointment_success() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setId(appointmentId);
        appt.setStatus(AppointmentStatus.PENDING);
        Patient apptPatient = new Patient();
        apptPatient.setId(patientId);
        appt.setPatient(apptPatient);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        when(appointmentRepository.save(appt)).thenReturn(appt);
        AppointmentResponseDTO dto = new AppointmentResponseDTO();
        when(appointmentMapper.toAppointmentResponseDTO(appt)).thenReturn(dto);

        assertThat(service.checkInMyAppointment(auth, appointmentId, Locale.US)).isEqualTo(dto);
        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    @Test void checkInMyAppointment_cancelled_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.CANCELLED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.checkInMyAppointment(auth, appointmentId, Locale.US))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cancelled");
    }

    @Test void checkInMyAppointment_completed_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.COMPLETED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.checkInMyAppointment(auth, appointmentId, Locale.US))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("completed");
    }

    @Test void checkInMyAppointment_alreadyCheckedIn_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.CHECKED_IN);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.checkInMyAppointment(auth, appointmentId, Locale.US))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already checked in");
    }

    @Test void checkInMyAppointment_notOwned_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.PENDING);
        Patient other = new Patient(); other.setId(UUID.randomUUID());
        appt.setPatient(other);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.checkInMyAppointment(auth, appointmentId, Locale.US))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void checkInMyAppointment_notFound_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkInMyAppointment(auth, appointmentId, Locale.US))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Feature 14: Appointment Booking ─────────────────────────────────

    @Test void getMyDepartments_delegatesWithHospital() {
        stubAuth();
        List<DepartmentMinimalDTO> expected = List.of(new DepartmentMinimalDTO());
        when(departmentService.getActiveDepartmentsMinimal(hospitalId, Locale.US)).thenReturn(expected);
        assertThat(service.getMyDepartments(auth, Locale.US)).isEqualTo(expected);
    }

    @Test void getMyDepartments_nullHospital_returnsEmpty() {
        patient.setHospitalId(null);
        stubAuth();
        when(registrationRepository.findByPatientId(patientId)).thenReturn(Collections.emptyList());
        assertThat(service.getMyDepartments(auth, Locale.US)).isEmpty();
    }

    @Test void getDepartmentProviders_delegates() {
        UUID deptId = UUID.randomUUID();
        DepartmentWithStaffDTO dept = new DepartmentWithStaffDTO();
        StaffMinimalDTO staff = new StaffMinimalDTO();
        dept.setStaffMembers(List.of(staff));
        when(departmentService.getDepartmentWithStaff(deptId, Locale.US)).thenReturn(dept);
        assertThat(service.getDepartmentProviders(auth, deptId, Locale.US)).containsExactly(staff);
    }

    @Test void getDepartmentProviders_nullStaffMembers_returnsEmpty() {
        UUID deptId = UUID.randomUUID();
        DepartmentWithStaffDTO dept = new DepartmentWithStaffDTO();
        dept.setStaffMembers(null);
        when(departmentService.getDepartmentWithStaff(deptId, Locale.US)).thenReturn(dept);
        assertThat(service.getDepartmentProviders(auth, deptId, Locale.US)).isEmpty();
    }

    @Test void bookMyAppointment_success() {
        stubAuth();
        UUID deptId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        PortalAppointmentRequestDTO dto = PortalAppointmentRequestDTO.builder()
                .departmentId(deptId)
                .staffId(staffId)
                .appointmentDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(9, 30))
                .reason("Checkup")
                .build();
        AppointmentSummaryDTO expected = new AppointmentSummaryDTO();
        expected.setId(UUID.randomUUID());
        when(appointmentService.createAppointment(any(AppointmentRequestDTO.class), eq(Locale.US), anyString()))
                .thenReturn(expected);
        when(auth.getName()).thenReturn("john.doe");
        assertThat(service.bookMyAppointment(auth, dto, Locale.US)).isEqualTo(expected);
    }

    // ── Feature 15: Pre-Visit Questionnaires ────────────────────────────

    @Test void getMyPendingQuestionnaires_filtersSubmitted() {
        stubAuth();
        PreVisitQuestionnaire q1 = new PreVisitQuestionnaire();
        q1.setId(UUID.randomUUID()); q1.setHospitalId(hospitalId);
        PreVisitQuestionnaire q2 = new PreVisitQuestionnaire();
        q2.setId(UUID.randomUUID()); q2.setHospitalId(hospitalId);

        when(preVisitQuestionnaireRepository.findByHospitalIdAndActiveTrue(hospitalId))
                .thenReturn(List.of(q1, q2));
        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, q1.getId()))
                .thenReturn(true);
        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, q2.getId()))
                .thenReturn(false);
        PreVisitQuestionnaireDTO dto = PreVisitQuestionnaireDTO.builder().id(UUID.randomUUID()).build();
        when(questionnaireMapper.toPreVisitQuestionnaireDTO(q2)).thenReturn(dto);

        assertThat(service.getMyPendingQuestionnaires(auth)).containsExactly(dto);
    }

    @Test void getMyPendingQuestionnaires_nullHospital_returnsEmpty() {
        patient.setHospitalId(null);
        stubAuth();
        when(registrationRepository.findByPatientId(patientId)).thenReturn(Collections.emptyList());
        assertThat(service.getMyPendingQuestionnaires(auth)).isEmpty();
    }

    @Test void getMySubmittedQuestionnaires_delegates() {
        stubAuth();
        QuestionnaireResponse r = QuestionnaireResponse.builder()
                .patientId(patientId).build();
        when(questionnaireResponseRepository.findByPatientId(patientId)).thenReturn(List.of(r));
        QuestionnaireResponseDTO dto = QuestionnaireResponseDTO.builder().id(UUID.randomUUID()).build();
        when(questionnaireMapper.toQuestionnaireResponseDTO(r)).thenReturn(dto);
        assertThat(service.getMySubmittedQuestionnaires(auth)).containsExactly(dto);
    }

    @Test void submitMyQuestionnaire_success() throws Exception {
        stubAuth();
        UUID qId = UUID.randomUUID();
        QuestionnaireResponseSubmitDTO dto = new QuestionnaireResponseSubmitDTO();
        dto.setQuestionnaireId(qId);
        dto.setAnswers(Map.of("q1", "a1"));

        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, qId))
                .thenReturn(false);
        PreVisitQuestionnaire q = new PreVisitQuestionnaire();
        q.setId(qId); q.setHospitalId(hospitalId); q.setTitle("Pre-Visit");
        when(preVisitQuestionnaireRepository.findById(qId)).thenReturn(Optional.of(q));
        when(objectMapper.writeValueAsString(dto.getAnswers())).thenReturn("{\"q1\":\"a1\"}");
        when(questionnaireResponseRepository.save(any())).thenAnswer(i -> {
            QuestionnaireResponse r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        QuestionnaireResponseDTO responseDTO = QuestionnaireResponseDTO.builder().id(UUID.randomUUID()).build();
        when(questionnaireMapper.toQuestionnaireResponseDTO(any())).thenReturn(responseDTO);

        assertThat(service.submitMyQuestionnaire(auth, dto)).isEqualTo(responseDTO);
    }

    @Test void submitMyQuestionnaire_alreadySubmitted_throws() {
        stubAuth();
        UUID qId = UUID.randomUUID();
        QuestionnaireResponseSubmitDTO dto = new QuestionnaireResponseSubmitDTO();
        dto.setQuestionnaireId(qId);
        dto.setAnswers(Map.of());
        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, qId))
                .thenReturn(true);
        assertThatThrownBy(() -> service.submitMyQuestionnaire(auth, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already submitted");
    }

    @Test void submitMyQuestionnaire_wrongHospital_throws() {
        stubAuth();
        UUID qId = UUID.randomUUID();
        QuestionnaireResponseSubmitDTO dto = new QuestionnaireResponseSubmitDTO();
        dto.setQuestionnaireId(qId);
        dto.setAnswers(Map.of());
        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, qId))
                .thenReturn(false);
        PreVisitQuestionnaire q = new PreVisitQuestionnaire();
        q.setId(qId); q.setHospitalId(UUID.randomUUID()); // different hospital
        when(preVisitQuestionnaireRepository.findById(qId)).thenReturn(Optional.of(q));
        assertThatThrownBy(() -> service.submitMyQuestionnaire(auth, dto))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void submitMyQuestionnaire_notFound_throws() {
        stubAuth();
        UUID qId = UUID.randomUUID();
        QuestionnaireResponseSubmitDTO dto = new QuestionnaireResponseSubmitDTO();
        dto.setQuestionnaireId(qId);
        dto.setAnswers(Map.of());
        when(questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(patientId, qId))
                .thenReturn(false);
        when(preVisitQuestionnaireRepository.findById(qId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitMyQuestionnaire(auth, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Feature 16: OpenNotes ───────────────────────────────────────────

    @Test void getMyEncounterNote_success() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        EncounterNote note = new EncounterNote();
        Patient notePatient = new Patient(); notePatient.setId(patientId);
        note.setPatient(notePatient);
        when(encounterNoteRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(note));
        EncounterNoteResponseDTO dto = new EncounterNoteResponseDTO();
        when(encounterMapper.toEncounterNoteResponseDTO(note)).thenReturn(dto);
        assertThat(service.getMyEncounterNote(auth, encounterId)).isEqualTo(dto);
    }

    @Test void getMyEncounterNote_notOwned_throws() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        EncounterNote note = new EncounterNote();
        Patient other = new Patient(); other.setId(UUID.randomUUID());
        note.setPatient(other);
        when(encounterNoteRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(note));
        assertThatThrownBy(() -> service.getMyEncounterNote(auth, encounterId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void getMyEncounterNote_notFound_throws() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        when(encounterNoteRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMyEncounterNote(auth, encounterId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Feature 17: Post-Visit Instructions ─────────────────────────────

    @Test void getMyPostVisitInstructions_success() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        DischargeSummaryResponseDTO summary = new DischargeSummaryResponseDTO();
        summary.setId(UUID.randomUUID());
        summary.setPatientId(patientId);
        summary.setEncounterId(encounterId);
        summary.setDischargeDiagnosis("Flu");
        when(dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, Locale.US))
                .thenReturn(summary);
        var result = service.getMyPostVisitInstructions(auth, encounterId, Locale.US);
        assertThat(result.getDischargeDiagnosis()).isEqualTo("Flu");
    }

    @Test void getMyPostVisitInstructions_notOwned_throws() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        DischargeSummaryResponseDTO summary = new DischargeSummaryResponseDTO();
        summary.setPatientId(UUID.randomUUID());
        when(dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, Locale.US))
                .thenReturn(summary);
        assertThatThrownBy(() -> service.getMyPostVisitInstructions(auth, encounterId, Locale.US))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void getMyPostVisitInstructions_noSummary_throws() {
        stubAuth();
        UUID encounterId = UUID.randomUUID();
        when(dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, Locale.US))
                .thenThrow(new ResourceNotFoundException("Not found"));
        assertThatThrownBy(() -> service.getMyPostVisitInstructions(auth, encounterId, Locale.US))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Feature 18: Immunization Certificate ────────────────────────────

    @Test void generateMyImmunizationCertificate_delegates() {
        stubAuth();
        List<ImmunizationResponseDTO> immunizations = List.of(new ImmunizationResponseDTO());
        when(immunizationService.getImmunizationsByPatientId(patientId)).thenReturn(immunizations);
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(immunizationCertificatePdfService.generate("John Doe", immunizations)).thenReturn(pdf);
        assertThat(service.generateMyImmunizationCertificate(auth)).isEqualTo(pdf);
    }

    // ── Feature 19: Health Maintenance Reminders ────────────────────────

    @Test void getMyHealthReminders_returnsAll() {
        stubAuth();
        HealthMaintenanceReminder r = HealthMaintenanceReminder.builder()
                .patientId(patientId)
                .type(HealthMaintenanceReminderType.ANNUAL_PHYSICAL)
                .status(HealthMaintenanceReminderStatus.PENDING)
                .dueDate(LocalDate.now().plusDays(7))
                .build();
        r.setId(UUID.randomUUID());
        when(healthMaintenanceReminderRepository.findByPatientIdAndActiveTrue(patientId))
                .thenReturn(List.of(r));
        var result = service.getMyHealthReminders(auth);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("ANNUAL_PHYSICAL");
    }

    @Test void completeMyHealthReminder_success() {
        stubAuth();
        UUID reminderId = UUID.randomUUID();
        HealthMaintenanceReminder r = HealthMaintenanceReminder.builder()
                .patientId(patientId)
                .status(HealthMaintenanceReminderStatus.PENDING)
                .build();
        r.setId(reminderId);
        when(healthMaintenanceReminderRepository.findById(reminderId)).thenReturn(Optional.of(r));
        when(healthMaintenanceReminderRepository.save(r)).thenReturn(r);

        service.completeMyHealthReminder(auth, reminderId);
        assertThat(r.getStatus()).isEqualTo(HealthMaintenanceReminderStatus.COMPLETED);
        assertThat(r.getCompletedDate()).isEqualTo(LocalDate.now());
    }

    @Test void completeMyHealthReminder_notOwned_throws() {
        stubAuth();
        UUID reminderId = UUID.randomUUID();
        HealthMaintenanceReminder r = HealthMaintenanceReminder.builder()
                .patientId(UUID.randomUUID())
                .build();
        r.setId(reminderId);
        when(healthMaintenanceReminderRepository.findById(reminderId)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.completeMyHealthReminder(auth, reminderId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void completeMyHealthReminder_notFound_throws() {
        stubAuth();
        UUID reminderId = UUID.randomUUID();
        when(healthMaintenanceReminderRepository.findById(reminderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeMyHealthReminder(auth, reminderId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void healthReminderDTO_overdue_dueDatePast() {
        stubAuth();
        HealthMaintenanceReminder r = HealthMaintenanceReminder.builder()
                .patientId(patientId)
                .type(HealthMaintenanceReminderType.FLU_SHOT)
                .status(HealthMaintenanceReminderStatus.PENDING)
                .dueDate(LocalDate.now().minusDays(5))
                .build();
        r.setId(UUID.randomUUID());
        when(healthMaintenanceReminderRepository.findByPatientIdAndActiveTrue(patientId))
                .thenReturn(List.of(r));
        var result = service.getMyHealthReminders(auth);
        assertThat(result.get(0).isOverdue()).isTrue();
    }

    @Test void healthReminderDTO_overdueStatus() {
        stubAuth();
        HealthMaintenanceReminder r = HealthMaintenanceReminder.builder()
                .patientId(patientId)
                .status(HealthMaintenanceReminderStatus.OVERDUE)
                .dueDate(LocalDate.now().plusDays(5))
                .build();
        r.setId(UUID.randomUUID());
        when(healthMaintenanceReminderRepository.findByPatientIdAndActiveTrue(patientId))
                .thenReturn(List.of(r));
        var result = service.getMyHealthReminders(auth);
        assertThat(result.get(0).isOverdue()).isTrue();
    }

    // ── Feature 20: Treatment Progress ──────────────────────────────────

    @Test void getMyTreatmentPlanProgress_success() {
        stubAuth();
        UUID planId = UUID.randomUUID();
        TreatmentPlanResponseDTO plan = new TreatmentPlanResponseDTO();
        plan.setPatientId(patientId);
        when(treatmentPlanService.getById(planId)).thenReturn(plan);

        TreatmentProgressEntry entry = TreatmentProgressEntry.builder()
                .treatmentPlanId(planId)
                .patientId(patientId)
                .progressDate(LocalDate.now())
                .progressNote("Good")
                .selfRating(8)
                .onTrack(true)
                .build();
        entry.setId(UUID.randomUUID());
        when(treatmentProgressEntryRepository.findByTreatmentPlanIdOrderByProgressDateDesc(planId))
                .thenReturn(List.of(entry));

        var result = service.getMyTreatmentPlanProgress(auth, planId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProgressNote()).isEqualTo("Good");
    }

    @Test void getMyTreatmentPlanProgress_notOwned_throws() {
        stubAuth();
        UUID planId = UUID.randomUUID();
        TreatmentPlanResponseDTO plan = new TreatmentPlanResponseDTO();
        plan.setPatientId(UUID.randomUUID());
        when(treatmentPlanService.getById(planId)).thenReturn(plan);
        assertThatThrownBy(() -> service.getMyTreatmentPlanProgress(auth, planId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void logMyTreatmentProgress_success() {
        stubAuth();
        UUID planId = UUID.randomUUID();
        TreatmentPlanResponseDTO plan = new TreatmentPlanResponseDTO();
        plan.setPatientId(patientId);
        when(treatmentPlanService.getById(planId)).thenReturn(plan);

        PortalProgressEntryRequestDTO request = PortalProgressEntryRequestDTO.builder()
                .progressDate(LocalDate.now())
                .progressNote("Improving")
                .selfRating(7)
                .onTrack(true)
                .build();
        when(treatmentProgressEntryRepository.save(any())).thenAnswer(i -> {
            TreatmentProgressEntry e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        var result = service.logMyTreatmentProgress(auth, planId, request);
        assertThat(result.getProgressNote()).isEqualTo("Improving");
        assertThat(result.getSelfRating()).isEqualTo(7);
    }

    @Test void logMyTreatmentProgress_defaultsDate() {
        stubAuth();
        UUID planId = UUID.randomUUID();
        TreatmentPlanResponseDTO plan = new TreatmentPlanResponseDTO();
        plan.setPatientId(patientId);
        when(treatmentPlanService.getById(planId)).thenReturn(plan);

        PortalProgressEntryRequestDTO request = PortalProgressEntryRequestDTO.builder()
                .progressDate(null)
                .progressNote("No date")
                .build();
        when(treatmentProgressEntryRepository.save(any())).thenAnswer(i -> {
            TreatmentProgressEntry e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        var result = service.logMyTreatmentProgress(auth, planId, request);
        assertThat(result.getProgressDate()).isEqualTo(LocalDate.now());
    }

    @Test void logMyTreatmentProgress_notOwned_throws() {
        stubAuth();
        UUID planId = UUID.randomUUID();
        TreatmentPlanResponseDTO plan = new TreatmentPlanResponseDTO();
        plan.setPatientId(UUID.randomUUID());
        when(treatmentPlanService.getById(planId)).thenReturn(plan);
        PortalProgressEntryRequestDTO request = PortalProgressEntryRequestDTO.builder().build();
        assertThatThrownBy(() -> service.logMyTreatmentProgress(auth, planId, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Feature 21: Patient-Reported Outcomes ───────────────────────────

    @Test void getMyOutcomes_returns() {
        stubAuth();
        PatientReportedOutcome o = PatientReportedOutcome.builder()
                .patientId(patientId)
                .outcomeType(PatientReportedOutcomeType.PAIN_SCORE)
                .score(3)
                .reportDate(LocalDate.now())
                .build();
        o.setId(UUID.randomUUID());
        when(patientReportedOutcomeRepository.findByPatientIdOrderByReportDateDesc(patientId))
                .thenReturn(List.of(o));
        var result = service.getMyOutcomes(auth);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(3);
        assertThat(result.get(0).getTypeLabel()).isEqualTo("Pain Score");
    }

    @Test void reportMyOutcome_success() {
        stubAuth();
        PortalOutcomeRequestDTO request = PortalOutcomeRequestDTO.builder()
                .outcomeType(PatientReportedOutcomeType.MOOD)
                .score(8)
                .notes("Feeling good")
                .reportDate(LocalDate.now())
                .build();
        when(patientReportedOutcomeRepository.save(any())).thenAnswer(i -> {
            PatientReportedOutcome o = i.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var result = service.reportMyOutcome(auth, request);
        assertThat(result.getScore()).isEqualTo(8);
        assertThat(result.getOutcomeType()).isEqualTo(PatientReportedOutcomeType.MOOD);
    }

    @Test void reportMyOutcome_defaultsDate() {
        stubAuth();
        PortalOutcomeRequestDTO request = PortalOutcomeRequestDTO.builder()
                .outcomeType(PatientReportedOutcomeType.FATIGUE)
                .score(5)
                .reportDate(null)
                .build();
        when(patientReportedOutcomeRepository.save(any())).thenAnswer(i -> {
            PatientReportedOutcome o = i.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var result = service.reportMyOutcome(auth, request);
        assertThat(result.getReportDate()).isEqualTo(LocalDate.now());
    }

    // ── Cancel Appointment ──────────────────────────────────────────────

    @Test void cancelMyAppointment_success() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setId(appointmentId);
        appt.setStatus(AppointmentStatus.PENDING);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);

        CancelAppointmentRequestDTO dto = new CancelAppointmentRequestDTO();
        dto.setAppointmentId(appointmentId);
        dto.setReason("Schedule conflict");

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        when(appointmentRepository.save(appt)).thenReturn(appt);
        AppointmentResponseDTO responseDTO = new AppointmentResponseDTO();
        when(appointmentMapper.toAppointmentResponseDTO(appt)).thenReturn(responseDTO);

        service.cancelMyAppointment(auth, dto, Locale.US);
        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appt.getNotes()).contains("Patient cancelled: Schedule conflict");
    }

    @Test void cancelMyAppointment_alreadyCancelled_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.CANCELLED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        CancelAppointmentRequestDTO dto = new CancelAppointmentRequestDTO();
        dto.setAppointmentId(appointmentId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.US))
                .isInstanceOf(BusinessException.class);
    }

    @Test void cancelMyAppointment_completed_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.COMPLETED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        CancelAppointmentRequestDTO dto = new CancelAppointmentRequestDTO();
        dto.setAppointmentId(appointmentId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.US))
                .isInstanceOf(BusinessException.class);
    }

    @Test void cancelMyAppointment_withNullReason_noNotes() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setId(appointmentId);
        appt.setStatus(AppointmentStatus.PENDING);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        CancelAppointmentRequestDTO dto = new CancelAppointmentRequestDTO();
        dto.setAppointmentId(appointmentId);
        dto.setReason(null);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        when(appointmentRepository.save(appt)).thenReturn(appt);
        when(appointmentMapper.toAppointmentResponseDTO(appt)).thenReturn(new AppointmentResponseDTO());
        service.cancelMyAppointment(auth, dto, Locale.US);
        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    // ── Reschedule Appointment ──────────────────────────────────────────

    @Test void rescheduleMyAppointment_success() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setId(appointmentId);
        appt.setStatus(AppointmentStatus.PENDING);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);

        RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                .appointmentId(appointmentId)
                .newDate(LocalDate.now().plusDays(3))
                .newStartTime(java.time.LocalTime.of(10, 0))
                .newEndTime(java.time.LocalTime.of(10, 30))
                .reason("Better time")
                .build();

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        when(appointmentRepository.save(appt)).thenReturn(appt);
        when(appointmentMapper.toAppointmentResponseDTO(appt)).thenReturn(new AppointmentResponseDTO());

        service.rescheduleMyAppointment(auth, dto, Locale.US);
        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(appt.getNotes()).contains("Patient rescheduled: Better time");
    }

    @Test void rescheduleMyAppointment_completed_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.COMPLETED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                .appointmentId(appointmentId).build();
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.US))
                .isInstanceOf(BusinessException.class);
    }

    @Test void rescheduleMyAppointment_cancelled_throws() {
        stubAuth();
        UUID appointmentId = UUID.randomUUID();
        Appointment appt = new Appointment();
        appt.setStatus(AppointmentStatus.CANCELLED);
        Patient apptPatient = new Patient(); apptPatient.setId(patientId);
        appt.setPatient(apptPatient);
        RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                .appointmentId(appointmentId).build();
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appt));
        assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.US))
                .isInstanceOf(BusinessException.class);
    }

    // ── Notification Preferences ────────────────────────────────────────

    @Test void getMyNotificationPreferences_returnsList() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        NotificationPreference pref = NotificationPreference.builder()
                .notificationType(NotificationType.APPOINTMENT_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .enabled(true)
                .build();
        pref.setId(UUID.randomUUID());
        when(notificationPreferenceRepository.findByUser_Id(userId)).thenReturn(List.of(pref));
        var result = service.getMyNotificationPreferences(auth);
        assertThat(result).hasSize(1);
    }

    @Test void setMyNotificationPreference_createsNew() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        User user = new User(); user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(notificationPreferenceRepository.findByUser_Id(userId)).thenReturn(Collections.emptyList());
        NotificationPreferenceUpdateDTO dto = NotificationPreferenceUpdateDTO.builder()
                .notificationType(NotificationType.APPOINTMENT_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .enabled(true)
                .build();
        when(notificationPreferenceRepository.save(any())).thenAnswer(i -> {
            NotificationPreference p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        NotificationPreferenceDTO result = service.setMyNotificationPreference(auth, dto);
        assertThat(result).isNotNull();
    }

    @Test void setMyNotificationPreference_updatesExisting() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        User user = new User(); user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        NotificationPreference existing = NotificationPreference.builder()
                .user(user)
                .notificationType(NotificationType.APPOINTMENT_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .enabled(false)
                .build();
        existing.setId(UUID.randomUUID());
        when(notificationPreferenceRepository.findByUser_Id(userId)).thenReturn(List.of(existing));
        NotificationPreferenceUpdateDTO dto = NotificationPreferenceUpdateDTO.builder()
                .notificationType(NotificationType.APPOINTMENT_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .enabled(true)
                .build();
        when(notificationPreferenceRepository.save(existing)).thenReturn(existing);
        service.setMyNotificationPreference(auth, dto);
        assertThat(existing.isEnabled()).isTrue();
    }

    @Test void resetMyNotificationPreferences_delegates() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        service.resetMyNotificationPreferences(auth);
        verify(notificationPreferenceRepository).deleteByUser_Id(userId);
    }

    // ── Vital Trends ────────────────────────────────────────────────────

    @Test void getMyVitalTrends_delegates() {
        stubAuth();
        when(vitalSignService.getVitals(eq(patientId), any(), any(), any(), eq(0), eq(500)))
                .thenReturn(Collections.emptyList());
        var result = service.getMyVitalTrends(auth, 6);
        assertThat(result).isEmpty();
    }

    @Test void getMyVitalTrends_clampsMinMonths() {
        stubAuth();
        when(vitalSignService.getVitals(eq(patientId), any(), any(), any(), eq(0), eq(500)))
                .thenReturn(Collections.emptyList());
        service.getMyVitalTrends(auth, 0);
        verify(vitalSignService).getVitals(eq(patientId), any(), any(), any(), eq(0), eq(500));
    }

    @Test void getMyVitalTrends_clampsMaxMonths() {
        stubAuth();
        when(vitalSignService.getVitals(eq(patientId), any(), any(), any(), eq(0), eq(500)))
                .thenReturn(Collections.emptyList());
        service.getMyVitalTrends(auth, 100);
        verify(vitalSignService).getVitals(eq(patientId), any(), any(), any(), eq(0), eq(500));
    }

    // ── Upcoming Vaccinations ───────────────────────────────────────────

    @Test void getMyUpcomingVaccinations_delegates() {
        stubAuth();
        when(immunizationService.getUpcomingImmunizations(eq(patientId), any(), any()))
                .thenReturn(List.of(new ImmunizationResponseDTO()));
        var result = service.getMyUpcomingVaccinations(auth, 6);
        assertThat(result).hasSize(1);
    }

    @Test void getMyUpcomingVaccinations_clampsMonths() {
        stubAuth();
        when(immunizationService.getUpcomingImmunizations(eq(patientId), any(), any()))
                .thenReturn(Collections.emptyList());
        service.getMyUpcomingVaccinations(auth, 0);
        service.getMyUpcomingVaccinations(auth, 50);
        verify(immunizationService, org.mockito.Mockito.times(2))
                .getUpcomingImmunizations(eq(patientId), any(), any());
    }

    // ── Proxy Management ────────────────────────────────────────────────

    @Test void grantProxy_success() {
        stubAuth();
        UUID proxyUserId = UUID.randomUUID();
        User proxyUser = new User(); proxyUser.setId(proxyUserId);
        proxyUser.setFirstName("Proxy"); proxyUser.setLastName("User");
        proxyUser.setUsername("proxyuser");
        when(userRepository.findByUsername("proxyuser")).thenReturn(Optional.of(proxyUser));
        when(patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                patientId, proxyUserId, ProxyStatus.ACTIVE)).thenReturn(Optional.empty());
        when(patientProxyRepository.save(any())).thenAnswer(i -> {
            PatientProxy p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        ProxyGrantRequestDTO dto = ProxyGrantRequestDTO.builder()
                .proxyUsername("proxyuser")
                .relationship(ProxyRelationship.SPOUSE)
                .permissions("VIEW_RECORDS")
                .build();
        var result = service.grantProxy(auth, dto);
        assertThat(result).isNotNull();
    }

    @Test void grantProxy_selfProxy_throws() {
        stubAuth();
        User self = new User(); self.setId(userId);
        when(userRepository.findByUsername("self")).thenReturn(Optional.of(self));
        ProxyGrantRequestDTO dto = ProxyGrantRequestDTO.builder()
                .proxyUsername("self")
                .relationship(ProxyRelationship.PARENT)
                .build();
        assertThatThrownBy(() -> service.grantProxy(auth, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yourself");
    }

    @Test void grantProxy_duplicateActive_throws() {
        stubAuth();
        UUID proxyUserId = UUID.randomUUID();
        User proxyUser = new User(); proxyUser.setId(proxyUserId);
        when(userRepository.findByUsername("proxyuser")).thenReturn(Optional.of(proxyUser));
        PatientProxy existing = PatientProxy.builder().build();
        when(patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                patientId, proxyUserId, ProxyStatus.ACTIVE)).thenReturn(Optional.of(existing));
        ProxyGrantRequestDTO dto = ProxyGrantRequestDTO.builder()
                .proxyUsername("proxyuser")
                .relationship(ProxyRelationship.PARENT)
                .build();
        assertThatThrownBy(() -> service.grantProxy(auth, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test void revokeProxy_success() {
        stubAuth();
        UUID proxyId = UUID.randomUUID();
        Patient grantor = new Patient(); grantor.setId(patientId);
        PatientProxy proxy = PatientProxy.builder()
                .grantorPatient(grantor)
                .status(ProxyStatus.ACTIVE)
                .build();
        proxy.setId(proxyId);
        when(patientProxyRepository.findById(proxyId)).thenReturn(Optional.of(proxy));
        when(patientProxyRepository.save(proxy)).thenReturn(proxy);
        service.revokeProxy(auth, proxyId);
        assertThat(proxy.getStatus()).isEqualTo(ProxyStatus.REVOKED);
    }

    @Test void revokeProxy_notOwned_throws() {
        stubAuth();
        UUID proxyId = UUID.randomUUID();
        Patient other = new Patient(); other.setId(UUID.randomUUID());
        PatientProxy proxy = PatientProxy.builder()
                .grantorPatient(other)
                .build();
        proxy.setId(proxyId);
        when(patientProxyRepository.findById(proxyId)).thenReturn(Optional.of(proxy));
        assertThatThrownBy(() -> service.revokeProxy(auth, proxyId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── resolvePatientHospitalId via education resources ────────────────

    @Test void getMyEducationResources_fallbackToRegistration() {
        patient.setHospitalId(null);
        stubAuth();
        var registration = org.mockito.Mockito.mock(
                com.example.hms.model.PatientHospitalRegistration.class);
        var hospital = new com.example.hms.model.Hospital();
        hospital.setId(hospitalId);
        when(registration.isActive()).thenReturn(true);
        when(registration.getHospital()).thenReturn(hospital);
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(registration));
        List<EducationResourceResponseDTO> expected = List.of(new EducationResourceResponseDTO());
        when(patientEducationService.getAllResources(hospitalId)).thenReturn(expected);
        assertThat(service.getMyEducationResources(auth)).isEqualTo(expected);
    }
}
