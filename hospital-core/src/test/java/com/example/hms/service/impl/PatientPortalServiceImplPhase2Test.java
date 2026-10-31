package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalBookAppointmentRequestDTO;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.Role;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.provider.ClinicalHospitals;
import com.example.hms.service.StaffAvailabilityService;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImmunizationService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import org.mockito.Spy;
import org.springframework.context.MessageSource;
import com.example.hms.i18n.TestMessageSources;

/**
 * Unit tests for Phase 2 patient portal service methods.
 * Tests cover: cancel/reschedule appointments,
 * home vitals, medication refills, after-visit summaries, care team, access log.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplPhase2Test {

    /** QA D978: a patient books in the future only, so the fixture dates move with the clock. */
    private static final LocalDate RESCHEDULE_DATE = LocalDate.now().plusDays(20);
    private static final LocalDate BOOKING_DATE = LocalDate.now().plusDays(30);

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientLabResultService labResultService;
    @Mock private PatientMedicationService medicationService;
    @Mock private PatientVitalSignService vitalSignService;
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
    @Mock private com.example.hms.service.disclosure.DisclosureAccountingService disclosureAccountingService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private com.example.hms.repository.HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private com.example.hms.repository.EncounterRepository encounterRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private StaffAvailabilityService staffAvailabilityService;
    @Mock private com.example.hms.repository.PatientProxyRepository patientProxyRepository;
    @Mock private com.example.hms.repository.UserRepository userRepository;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private com.example.hms.service.EmailService emailService;
    @Mock private com.example.hms.service.scheduling.SlotInventoryService slotInventoryService;
    @Mock private com.example.hms.service.webhook.WebhookPublisher webhookPublisher;
    @Mock private com.example.hms.service.i18n.PatientLocaleResolver patientLocaleResolver;
    @Mock private com.example.hms.repository.QuestionnaireRepository questionnaireRepository;
    @Mock private com.example.hms.repository.QuestionnaireResponseRepository questionnaireResponseRepository;
    @Mock private com.example.hms.mapper.QuestionnaireMapper questionnaireMapper;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @Mock private com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;

    /** QA D1060: the slot rules, stubbed to their 30-minute fallback (SlotPolicyStubs). */
    @org.mockito.Mock
    private com.example.hms.service.scheduling.AppointmentSlotPolicy slotPolicy;

    @InjectMocks
    private PatientPortalServiceImpl service;

    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
        com.example.hms.testsupport.SlotPolicyStubs.defaultLength(slotPolicy);
        com.example.hms.testsupport.SlotPolicyStubs.publishedSlots(slotPolicy);
        // findClinicalById answers as the database does: the stubbed row, when it is a hospital.
        lenient().when(hospitalRepository.findClinicalById(any()))
            .thenAnswer(invocation -> hospitalRepository.findById(invocation.getArgument(0))
                .filter(ClinicalHospitals::isClinical));
        userId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setUsername("patient.jane");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setUser(user);
    }

    /** Common stub: resolvePatientId/findPatient succeeds. */
    private void stubPatientResolution() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
    }

    /** Build a stub appointment owned by the test patient. */
    private Appointment buildAppointment(UUID appointmentId, AppointmentStatus status) {
        Appointment appt = new Appointment();
        appt.setId(appointmentId);
        appt.setStatus(status);
        appt.setPatient(patient);
        appt.setAppointmentDate(LocalDate.of(2026, 4, 15));
        appt.setStartTime(LocalTime.of(9, 0));
        appt.setEndTime(LocalTime.of(9, 30));
        return appt;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cancel Own Appointment
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cancelMyAppointment")
    class CancelMyAppointment {

        @Test
        @DisplayName("should cancel a scheduled appointment successfully")
        void cancelScheduledAppointment_success() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            appointment.setNotes(null);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

            AppointmentResponseDTO expectedDto = AppointmentResponseDTO.builder().id(apptId).build();
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(expectedDto);

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .reason("Schedule conflict")
                    .build();

            AppointmentResponseDTO result = service.cancelMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(apptId);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(appointment.getNotes()).contains("Patient cancelled: Schedule conflict");
            verify(appointmentRepository).save(appointment);
            // The appointment owns its slot (P3 #22): a portal cancel frees
            // the time just like a desk cancel.
            verify(slotInventoryService).releaseForAppointment(apptId);
        }

        @Test
        @DisplayName("a hospital-backed portal cancel announces APPOINTMENT_CANCELLED to item 45's outbox")
        void cancelEmitsTheCancellationWebhook() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            appointment.setHospital(hospital);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any(Appointment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());

            service.cancelMyAppointment(auth,
                    CancelAppointmentRequestDTO.builder().appointmentId(apptId).build(),
                    Locale.ENGLISH);

            // The portal cancel is its own write path - a subscriber must
            // see it like a desk cancel, keyed by hospital and appointment.
            verify(webhookPublisher).publish(hospital.getId(),
                    com.example.hms.enums.platform.WebhookEventType.APPOINTMENT_CANCELLED,
                    "Appointment", apptId);
        }

        @Test
        @DisplayName("should cancel without reason — notes remain unchanged")
        void cancelWithoutReason_notesUnchanged() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.CONFIRMED);
            appointment.setNotes("Existing note");

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .reason(null)
                    .build();

            service.cancelMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(appointment.getNotes()).isEqualTo("Existing note");
        }

        @Test
        @DisplayName("should throw when appointment not found")
        void appointmentNotFound_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId).build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should throw when appointment belongs to different patient")
        void notOwner_throwsAccessDenied() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());

            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            appointment.setPatient(otherPatient);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId).build();

            // Another patient's appointment answers exactly as a missing one.
            String refused = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH)).getMessage();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());
            String missing = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH)).getMessage();
            assertThat(refused).isEqualTo(missing);
        }

        @Test
        @DisplayName("should throw when appointment is already cancelled")
        void alreadyCancelled_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.CANCELLED);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId).build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.alreadyCancelled");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = AppointmentStatus.class,
                names = {"CHECKED_IN", "IN_PROGRESS", "NO_SHOW", "FAILED"})
        @DisplayName("QA D717: an arrived or closed-out appointment cannot be cancelled from the portal")
        void arrivedAppointment_cannotBeCancelled(AppointmentStatus status) {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, status);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId).reason("Changement").build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.FRENCH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.patient.notChangeable");
            assertThat(appointment.getStatus()).isEqualTo(status);
            verify(appointmentRepository, never()).save(any());
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = AppointmentStatus.class,
                names = {"CHECKED_IN", "IN_PROGRESS", "NO_SHOW", "FAILED"})
        @DisplayName("QA D717 sibling: an arrived appointment cannot be rescheduled from the portal")
        void arrivedAppointment_cannotBeRescheduled(AppointmentStatus status) {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, status);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.FRENCH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.patient.notChangeable");
            assertThat(appointment.getStatus()).isEqualTo(status);
            verify(appointmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw when appointment is completed")
        void completed_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.COMPLETED);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId).build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.cancel.completed");
        }

        @Test
        @DisplayName("should append cancel reason to existing notes with separator")
        void appendsToExistingNotes() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            appointment.setNotes("Doctor note");

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .reason("Emergency")
                    .build();

            service.cancelMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(appointment.getNotes()).isEqualTo("Doctor note | Patient cancelled: Emergency");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reschedule Own Appointment
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("rescheduleMyAppointment")
    class RescheduleMyAppointment {

        @Test
        @DisplayName("a hospital-backed portal reschedule announces APPOINTMENT_RESCHEDULED to item 45's outbox")
        void rescheduleEmitsTheRescheduledWebhook() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            appointment.setHospital(hospital);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());

            service.rescheduleMyAppointment(auth, RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build(), Locale.ENGLISH);

            verify(webhookPublisher).publish(hospital.getId(),
                    com.example.hms.enums.platform.WebhookEventType.APPOINTMENT_RESCHEDULED,
                    "Appointment", apptId);
        }

        @Test
        @DisplayName("should reschedule a scheduled appointment successfully")
        void reschedule_success() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AppointmentResponseDTO expectedDto = AppointmentResponseDTO.builder().id(apptId).build();
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(expectedDto);

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .reason("Need afternoon slot")
                    .build();

            AppointmentResponseDTO result = service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(result).isNotNull();
            assertThat(appointment.getAppointmentDate()).isEqualTo(RESCHEDULE_DATE);
            assertThat(appointment.getStartTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(appointment.getEndTime()).isEqualTo(LocalTime.of(14, 30));
            // QA D977: a moved appointment is booked at its new time, as the desk lands it.
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
            assertThat(appointment.getNotes()).contains("Patient rescheduled: Need afternoon slot");
            verify(appointmentRepository).save(appointment);
        }

        @Test
        @DisplayName("review round 13: a tentative (PENDING) booking moved by the patient stays tentative")
        void reschedule_pendingStaysPending() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.PENDING);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());

            service.rescheduleMyAppointment(auth, RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build(), Locale.ENGLISH);

            assertThat(appointment.getAppointmentDate()).isEqualTo(RESCHEDULE_DATE);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        }

        @Test
        @DisplayName("should throw when appointment not found")
        void notFound_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should throw when appointment belongs to different patient")
        void notOwner_throwsAccessDenied() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());

            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            appointment.setPatient(otherPatient);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            String refused = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH)).getMessage();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());
            String missing = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH)).getMessage();
            assertThat(refused).isEqualTo(missing);
        }

        @Test
        @DisplayName("should throw when appointment is completed")
        void completed_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.COMPLETED);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.reschedule.completed");
        }

        @Test
        @DisplayName("should throw when appointment is cancelled")
        void cancelled_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.CANCELLED);

            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "appointment.reschedule.cancelled");
        }

        @Test
        @DisplayName("QA D1060: the patient's end time is ignored, the slot rules set the length")
        void reschedule_theSlotRulesSetTheLength() {
            // Was "should throw when end time is not after start time": the end
            // is no longer the patient's to send (the dialog saved 08:20-09:20).
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());
            // QA D1093: the length is the published slot's own (a 20-minute slot).
            when(slotPolicy.requirePublishedFreeSlot(any(), any(), any(), any()))
                    .thenReturn(com.example.hms.model.scheduling.AppointmentSlot.builder()
                            .startAt(java.time.LocalDateTime.of(RESCHEDULE_DATE, LocalTime.of(14, 0)))
                            .endAt(java.time.LocalDateTime.of(RESCHEDULE_DATE, LocalTime.of(14, 20)))
                            .build());

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(20, 0))
                    .build();

            service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(appointment.getStartTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(appointment.getEndTime()).isEqualTo(LocalTime.of(14, 20));
        }

        @Test
        @DisplayName("QA D1093: a reschedule to a time that is not a published, free slot is refused")
        void d1093_rescheduleOffGridIsRefused() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(slotPolicy.requirePublishedFreeSlot(any(), any(), any(), any()))
                    .thenThrow(new BusinessException("portal.booking.notAPublishedSlot"));

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId).newDate(RESCHEDULE_DATE).newStartTime(LocalTime.of(15, 0)).build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
            verify(appointmentRepository, never()).save(any());
            verify(slotInventoryService, never()).releaseForAppointment(any());
        }

        @Test
        @DisplayName("QA D1093: a reschedule frees the old slot and takes the new one")
        void d1093_rescheduleMovesTheSlot() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            appointment.setHospital(hospital);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(apptId).build());
            com.example.hms.model.scheduling.AppointmentSlot newSlot = com.example.hms.model.scheduling.AppointmentSlot
                    .builder().startAt(java.time.LocalDateTime.of(RESCHEDULE_DATE, LocalTime.of(14, 0)))
                    .endAt(java.time.LocalDateTime.of(RESCHEDULE_DATE, LocalTime.of(14, 20))).build();
            when(slotPolicy.requirePublishedFreeSlot(any(), eq(java.time.LocalDateTime.of(RESCHEDULE_DATE,
                    LocalTime.of(14, 0))), eq(hospital.getId()), eq(null))).thenReturn(newSlot);

            service.rescheduleMyAppointment(auth, RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId).newDate(RESCHEDULE_DATE).newStartTime(LocalTime.of(14, 0)).build(),
                    Locale.ENGLISH);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(slotInventoryService, slotPolicy);
            order.verify(slotInventoryService).releaseForAppointment(apptId);
            order.verify(slotPolicy).takeSlot(newSlot, appointment);
            assertThat(appointment.getEndTime()).isEqualTo(LocalTime.of(14, 20));
        }

        @Test
        @DisplayName("QA D1063: a reschedule onto a slot held for a waitlist offer is refused")
        void reschedule_ontoAHeldSlotIsRefused() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            Appointment appointment = buildAppointment(apptId, AppointmentStatus.SCHEDULED);
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.of(appointment));
            doThrow(new BusinessException("slot.heldForOffer"))
                    .when(slotPolicy).requireNoLiveHold(any(), any(), any());

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(RESCHEDULE_DATE)
                    .newStartTime(LocalTime.of(14, 0))
                    .build();

            com.example.hms.model.Staff clinician = new com.example.hms.model.Staff();
            clinician.setId(UUID.randomUUID());
            appointment.setStaff(clinician);
            when(staffAvailabilityService.isStaffAvailable(eq(clinician.getId()), any())).thenReturn(true);
            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "slot.heldForOffer");
            verify(appointmentRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Record Home Vital
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordHomeVital")
    class RecordHomeVital {

        @Test
        @DisplayName("should record vital with source=PATIENT_REPORTED and correct fields")
        void recordVital_success() {
            stubPatientResolution();

            HomeVitalReadingDTO dto = HomeVitalReadingDTO.builder()
                    .systolicBpMmHg(120)
                    .diastolicBpMmHg(80)
                    .heartRateBpm(72)
                    .spo2Percent(98)
                    .weightKg(73.5)
                    .bodyPosition("Sitting")
                    .notes("Morning reading")
                    .build();

            PatientVitalSignResponseDTO expectedResponse = new PatientVitalSignResponseDTO();
            when(vitalSignService.recordVital(eq(patientId), any(PatientVitalSignRequestDTO.class), eq(userId)))
                    .thenReturn(expectedResponse);

            PatientVitalSignResponseDTO result = service.recordHomeVital(auth, dto);

            assertThat(result).isEqualTo(expectedResponse);

            ArgumentCaptor<PatientVitalSignRequestDTO> captor =
                    ArgumentCaptor.forClass(PatientVitalSignRequestDTO.class);
            verify(vitalSignService).recordVital(eq(patientId), captor.capture(), eq(userId));

            PatientVitalSignRequestDTO captured = captor.getValue();
            assertThat(captured.getSource()).isEqualTo("PATIENT_REPORTED");
            assertThat(captured.getSystolicBpMmHg()).isEqualTo(120);
            assertThat(captured.getDiastolicBpMmHg()).isEqualTo(80);
            assertThat(captured.getHeartRateBpm()).isEqualTo(72);
            assertThat(captured.getSpo2Percent()).isEqualTo(98);
            assertThat(captured.getWeightKg()).isEqualTo(73.5);
            assertThat(captured.getBodyPosition()).isEqualTo("Sitting");
            assertThat(captured.getNotes()).isEqualTo("Morning reading");
            assertThat(captured.getRecordedAt()).isNotNull();
        }

        @Test
        @DisplayName("should use provided recordedAt when present")
        void recordVital_usesProvidedTimestamp() {
            stubPatientResolution();

            LocalDateTime timestamp = LocalDateTime.of(2026, 2, 15, 8, 30);
            HomeVitalReadingDTO dto = HomeVitalReadingDTO.builder()
                    .heartRateBpm(68)
                    .recordedAt(timestamp)
                    .build();

            when(vitalSignService.recordVital(any(), any(), any()))
                    .thenReturn(new PatientVitalSignResponseDTO());

            service.recordHomeVital(auth, dto);

            ArgumentCaptor<PatientVitalSignRequestDTO> captor =
                    ArgumentCaptor.forClass(PatientVitalSignRequestDTO.class);
            verify(vitalSignService).recordVital(any(), captor.capture(), any());

            assertThat(captor.getValue().getRecordedAt()).isEqualTo(timestamp);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Medication Refills
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("medication refills")
    class MedicationRefills {

        private Prescription buildPrescription(UUID prescriptionId, Patient owner) {
            Prescription rx = new Prescription();
            rx.setId(prescriptionId);
            rx.setPatient(owner);
            rx.setMedicationName("Metformin 500mg");
            // Prescription.status defaults to DRAFT, which is deliberately
            // un-refillable — an unsigned prescription was never issued. A
            // prescription a patient can request a refill against has been
            // signed, so the fixture says so rather than relying on the default.
            rx.setStatus(PrescriptionStatus.SIGNED);
            return rx;
        }

        private RefillRequest buildRefillRequest(UUID refillId, Patient owner,
                                                  Prescription rx, RefillStatus status) {
            RefillRequest r = new RefillRequest();
            r.setId(refillId);
            r.setPatient(owner);
            r.setPrescription(rx);
            r.setStatus(status);
            r.setPreferredPharmacy("CVS");
            r.setPatientNotes("Running low");
            r.setCreatedAt(LocalDateTime.now());
            r.setUpdatedAt(LocalDateTime.now());
            return r;
        }

        @Test
        @DisplayName("requestMedicationRefill — should create a REQUESTED refill")
        void requestRefill_success() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);

            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> {
                RefillRequest saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId)
                    .preferredPharmacy("CVS Pharmacy")
                    .notes("Need refill ASAP")
                    .build();

            MedicationRefillResponseDTO result = service.requestMedicationRefill(auth, dto);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("REQUESTED");
            assertThat(result.getMedicationName()).isEqualTo("Metformin 500mg");
            assertThat(result.getPreferredPharmacy()).isEqualTo("CVS Pharmacy");

            ArgumentCaptor<RefillRequest> captor = ArgumentCaptor.forClass(RefillRequest.class);
            verify(refillRequestRepository).save(captor.capture());
            RefillRequest saved = captor.getValue();
            assertThat(saved.getPatient()).isEqualTo(patient);
            assertThat(saved.getPrescription()).isEqualTo(prescription);
            assertThat(saved.getStatus()).isEqualTo(RefillStatus.REQUESTED);
        }

        @Test
        @DisplayName("requestMedicationRefill — refuses a second request while one is under review")
        void requestRefill_refusesDuplicate() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(refillRequestRepository
                    .findFirstByPrescription_IdAndPatient_IdAndStatusInOrderByCreatedAtDesc(
                            eq(rxId), eq(patient.getId()), any()))
                    .thenReturn(Optional.of(
                            buildRefillRequest(UUID.randomUUID(), patient, prescription,
                                    RefillStatus.REQUESTED)));

            assertThatThrownBy(() -> service.requestMedicationRefill(auth,
                    MedicationRefillRequestDTO.builder().prescriptionId(rxId).build()))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.alreadyPending".equals(((com.example.hms.exception.BusinessException) e).getMessageKey()));

            // There was no guard at all before: a double tap fired a second
            // care-team email and a second row in every prescriber's inbox.
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("requestMedicationRefill — a held request also blocks a new one")
        void requestRefill_pausedAlsoBlocks() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(refillRequestRepository
                    .findFirstByPrescription_IdAndPatient_IdAndStatusInOrderByCreatedAtDesc(
                            eq(rxId), eq(patient.getId()), any()))
                    .thenReturn(Optional.of(
                            buildRefillRequest(UUID.randomUUID(), patient, prescription,
                                    RefillStatus.PAUSED)));

            assertThatThrownBy(() -> service.requestMedicationRefill(auth,
                    MedicationRefillRequestDTO.builder().prescriptionId(rxId).build()))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.onHold".equals(((com.example.hms.exception.BusinessException) e).getMessageKey()));
        }

        @Test
        @DisplayName("requestMedicationRefill — refuses a prescription that can no longer be refilled")
        void requestRefill_refusesUnrefillablePrescription() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStatus(PrescriptionStatus.DISCONTINUED);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));

            // Without this the request is accepted, the whole care team is
            // emailed, and only the prescriber's approval attempt discovers the
            // prescription is dead.
            assertThatThrownBy(() -> service.requestMedicationRefill(auth,
                    MedicationRefillRequestDTO.builder().prescriptionId(rxId).build()))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.notRefillable".equals(((com.example.hms.exception.BusinessException) e).getMessageKey()));
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("QA D719: requestMedicationRefill — refuses a completed controlled substance")
        void requestRefill_refusesControlledSubstance() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            prescription.setControlledSubstance(true);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            // The real resolver answers true for the prescriber's tick.
            when(controlledResolver.isControlled(prescription)).thenReturn(true);

            MedicationRefillRequestDTO request = MedicationRefillRequestDTO.builder().prescriptionId(rxId).build();
            // QA D813: named as a controlled medicine, not as "no renewals left".
            assertThatThrownBy(() -> service.requestMedicationRefill(auth, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.controlled".equals(((com.example.hms.exception.BusinessException) e).getMessageKey()));
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("QA D719 re-test: requestMedicationRefill — refuses catalog-controlled morphine whose tick is false")
        void requestRefill_refusesCatalogControlledSubstance() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            prescription.setControlledSubstance(false);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(controlledResolver.isControlled(prescription)).thenReturn(true);

            MedicationRefillRequestDTO request = MedicationRefillRequestDTO.builder().prescriptionId(rxId).build();
            assertThatThrownBy(() -> service.requestMedicationRefill(auth, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.controlled".equals(((BusinessException) e).getMessageKey()));
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("QA D813: catalog-controlled morphine with renewals left on paper is refused as controlled")
        void requestRefill_controlledWithRenewalsLeftSaysControlled() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            prescription.setControlledSubstance(false);
            prescription.setRefillsAllowed(3);
            prescription.setRefillsRemaining(3);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(controlledResolver.isControlled(prescription)).thenReturn(true);

            MedicationRefillRequestDTO request = MedicationRefillRequestDTO.builder().prescriptionId(rxId).build();
            assertThatThrownBy(() -> service.requestMedicationRefill(auth, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.controlled".equals(((BusinessException) e).getMessageKey()));
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("QA D743: a duplicate refill request is refused with a localised key")
        void requestRefill_duplicateIsLocalised() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            RefillRequest open = RefillRequest.builder().status(RefillStatus.REQUESTED).build();
            when(refillRequestRepository.findFirstByPrescription_IdAndPatient_IdAndStatusInOrderByCreatedAtDesc(
                    any(), any(), any())).thenReturn(Optional.of(open));

            MedicationRefillRequestDTO request = MedicationRefillRequestDTO.builder().prescriptionId(rxId).build();
            assertThatThrownBy(() -> service.requestMedicationRefill(auth, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "patient.refill.alreadyPending".equals(((BusinessException) e).getMessageKey()));
        }

        @Test
        @DisplayName("requestMedicationRefill — a dispensed prescription is still refillable")
        void requestRefill_allowsDispensedPrescription() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            // The normal state when a patient needs a refill: they already
            // collected the first fill.
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> {
                RefillRequest saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });

            assertThat(service.requestMedicationRefill(auth,
                    MedicationRefillRequestDTO.builder().prescriptionId(rxId).build())
                    .getStatus()).isEqualTo("REQUESTED");
        }

        @Test
        @DisplayName("requestMedicationRefill — should notify doctor and nurses in the care team")
        void requestRefill_notifiesDoctorAndNurseCareTeam() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            UUID hospitalId = UUID.randomUUID();
            UUID departmentId = UUID.randomUUID();

            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            Department department = new Department();
            department.setId(departmentId);

            Role doctorRole = new Role();
            doctorRole.setCode("ROLE_DOCTOR");
            UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
            doctorAssignment.setRole(doctorRole);
            User doctorUser = new User();
            doctorUser.setUsername("doctor.one");
            doctorUser.setEmail("doctor.one@hms.test");
            Staff doctor = new Staff();
            doctor.setUser(doctorUser);
            doctor.setHospital(hospital);
            doctor.setDepartment(department);
            doctor.setAssignment(doctorAssignment);

            Role nurseRole = new Role();
            nurseRole.setCode("ROLE_NURSE");
            UserRoleHospitalAssignment nurseAssignment = new UserRoleHospitalAssignment();
            nurseAssignment.setRole(nurseRole);
            User nurseUser = new User();
            nurseUser.setUsername("nurse.one");
            nurseUser.setEmail("nurse.one@hms.test");
            Staff nurse = new Staff();
            nurse.setUser(nurseUser);
            nurse.setHospital(hospital);
            nurse.setDepartment(department);
            nurse.setAssignment(nurseAssignment);

            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStaff(doctor);
            prescription.setHospital(hospital);

            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId))
                    .thenReturn(List.of(doctor, nurse));
            when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> {
                RefillRequest saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId)
                    .preferredPharmacy("CVS Pharmacy")
                    .notes("Need refill ASAP")
                    .build();

            MedicationRefillResponseDTO result = service.requestMedicationRefill(auth, dto);

            assertThat(result).isNotNull();
            verify(notificationService).created(any(), eq("doctor.one"), eq("MEDICATION_REFILL"));
            verify(notificationService).created(any(), eq("nurse.one"), eq("MEDICATION_REFILL"));
            verify(emailService).sendHtml(eq(List.of("doctor.one@hms.test")), eq(List.of()), eq(List.of()), eq("Demande de renouvellement d'ordonnance en attente de revue"), any());
            verify(emailService).sendHtml(eq(List.of("nurse.one@hms.test")), eq(List.of()), eq(List.of()), eq("Demande de renouvellement d'ordonnance en attente de revue"), any());
        }

        @Test
        @DisplayName("requestMedicationRefill — should still succeed if notification channels fail")
        void requestRefill_notificationFailuresDoNotFailRequest() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();

            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            Department department = new Department();
            department.setId(UUID.randomUUID());

            Role doctorRole = new Role();
            doctorRole.setCode("ROLE_DOCTOR");
            UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
            doctorAssignment.setRole(doctorRole);
            User doctorUser = new User();
            doctorUser.setUsername("doctor.fail");
            doctorUser.setEmail("doctor.fail@hms.test");
            Staff doctor = new Staff();
            doctor.setUser(doctorUser);
            doctor.setHospital(hospital);
            doctor.setDepartment(department);
            doctor.setAssignment(doctorAssignment);

            Prescription prescription = buildPrescription(rxId, patient);
            prescription.setStaff(doctor);
            prescription.setHospital(hospital);

            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospital.getId(), department.getId()))
                    .thenReturn(List.of(doctor));
            doThrow(new RuntimeException("notification down"))
                    .when(notificationService).created(any(), any(), eq("MEDICATION_REFILL"));
            doThrow(new RuntimeException("smtp down"))
                    .when(emailService).sendHtml(any(), any(), any(), eq("Demande de renouvellement d'ordonnance en attente de revue"), any());
            when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> {
                RefillRequest saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(LocalDateTime.now());
                saved.setUpdatedAt(LocalDateTime.now());
                return saved;
            });

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId)
                    .preferredPharmacy("CVS Pharmacy")
                    .notes("Need refill ASAP")
                    .build();

            MedicationRefillResponseDTO result = service.requestMedicationRefill(auth, dto);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("REQUESTED");
            verify(refillRequestRepository).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("requestMedicationRefill — should throw if prescription not found")
        void requestRefill_prescriptionNotFound_throws() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.empty());

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId).build();

            assertThatThrownBy(() -> service.requestMedicationRefill(auth, dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "prescription.notFound");
        }

        @Test
        @DisplayName("requestMedicationRefill — should throw if prescription belongs to another patient")
        void requestRefill_notOwner_throwsAccessDenied() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());
            Prescription prescription = buildPrescription(rxId, otherPatient);

            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(prescription));

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId).build();

            String refused = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.requestMedicationRefill(auth, dto)).getMessage();
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.empty());
            String missing = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.requestMedicationRefill(auth, dto)).getMessage();
            assertThat(refused).isEqualTo(missing);
        }

        @Test
        @DisplayName("getMyRefills — should return paginated refills")
        void getRefills_returnsPaged() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            RefillRequest refill = buildRefillRequest(UUID.randomUUID(), patient, prescription, RefillStatus.REQUESTED);

            Pageable pageable = PageRequest.of(0, 10);
            Page<RefillRequest> page = new PageImpl<>(List.of(refill), pageable, 1);
            when(refillRequestRepository.findByPatientId(patientId, pageable)).thenReturn(page);

            Page<MedicationRefillResponseDTO> result = service.getMyRefills(auth, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getMedicationName()).isEqualTo("Metformin 500mg");
        }

        @Test
        @DisplayName("cancelMyRefill — should cancel a REQUESTED refill")
        void cancelRefill_success() {
            stubPatientResolution();
            UUID refillId = UUID.randomUUID();
            UUID rxId = UUID.randomUUID();
            Prescription prescription = buildPrescription(rxId, patient);
            RefillRequest refill = buildRefillRequest(refillId, patient, prescription, RefillStatus.REQUESTED);

            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(refill));
            when(refillRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MedicationRefillResponseDTO result = service.cancelMyRefill(auth, refillId);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            verify(refillRequestRepository).save(refill);
        }

        @Test
        @DisplayName("cancelMyRefill — should throw if refill not found")
        void cancelRefill_notFound_throws() {
            stubPatientResolution();
            UUID refillId = UUID.randomUUID();
            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelMyRefill(auth, refillId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("cancelMyRefill — should throw if refill belongs to another patient")
        void cancelRefill_notOwner_throwsAccessDenied() {
            stubPatientResolution();
            UUID refillId = UUID.randomUUID();
            Patient otherPatient = new Patient();
            otherPatient.setId(UUID.randomUUID());
            Prescription prescription = buildPrescription(UUID.randomUUID(), otherPatient);
            RefillRequest refill = buildRefillRequest(refillId, otherPatient, prescription, RefillStatus.REQUESTED);

            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(refill));

            String refused = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.cancelMyRefill(auth, refillId)).getMessage();
            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.empty());
            String missing = catchThrowableOfType(ResourceNotFoundException.class,
                    () -> service.cancelMyRefill(auth, refillId)).getMessage();
            assertThat(refused).isEqualTo(missing);
            assertThat(refill.getStatus()).isEqualTo(RefillStatus.REQUESTED);
        }

        @Test
        @DisplayName("cancelMyRefill — should throw if refill is already APPROVED (not REQUESTED)")
        void cancelRefill_alreadyApproved_throws() {
            stubPatientResolution();
            UUID refillId = UUID.randomUUID();
            Prescription prescription = buildPrescription(UUID.randomUUID(), patient);
            RefillRequest refill = buildRefillRequest(refillId, patient, prescription, RefillStatus.APPROVED);

            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(refill));

            assertThatThrownBy(() -> service.cancelMyRefill(auth, refillId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only pending refill requests can be cancelled");
        }

        @Test
        @DisplayName("cancelMyRefill — should throw if refill is DENIED")
        void cancelRefill_denied_throws() {
            stubPatientResolution();
            UUID refillId = UUID.randomUUID();
            Prescription prescription = buildPrescription(UUID.randomUUID(), patient);
            RefillRequest refill = buildRefillRequest(refillId, patient, prescription, RefillStatus.DENIED);

            when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(refill));

            assertThatThrownBy(() -> service.cancelMyRefill(auth, refillId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only pending refill requests");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // After-Visit Summaries
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAfterVisitSummaries")
    class AfterVisitSummaries {

        @Test
        @DisplayName("should delegate to discharge summary service")
        void getAfterVisitSummaries_success() {
            stubPatientResolution();
            DischargeSummaryResponseDTO summary = new DischargeSummaryResponseDTO();
            when(dischargeSummaryService.getDischargeSummariesForPortalPatient(patientId))
                    .thenReturn(List.of(summary));

            List<DischargeSummaryResponseDTO> result =
                    service.getMyAfterVisitSummaries(auth, Locale.ENGLISH);

            assertThat(result).hasSize(1);
            verify(dischargeSummaryService).getDischargeSummariesForPortalPatient(patientId);
        }

        @Test
        @DisplayName("should return empty list when no summaries exist")
        void noSummaries_returnsEmpty() {
            stubPatientResolution();
            when(dischargeSummaryService.getDischargeSummariesForPortalPatient(patientId))
                    .thenReturn(List.of());

            List<DischargeSummaryResponseDTO> result =
                    service.getMyAfterVisitSummaries(auth, Locale.ENGLISH);

            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Care Team
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyCareTeam")
    class MyCareTeam {

        @Test
        @DisplayName("should return current PCP and history")
        void getCareTeam_withCurrentPcp() {
            stubPatientResolution();

            PatientPrimaryCareResponseDTO currentPcp = PatientPrimaryCareResponseDTO.builder()
                    .id(UUID.randomUUID())
                    .patientId(patientId)
                    .doctorDisplay("Dr. Smith")
                    .startDate(LocalDate.of(2025, 1, 1))
                    .current(true)
                    .build();

            PatientPrimaryCareResponseDTO pastPcp = PatientPrimaryCareResponseDTO.builder()
                    .id(UUID.randomUUID())
                    .patientId(patientId)
                    .doctorDisplay("Dr. Jones")
                    .startDate(LocalDate.of(2023, 1, 1))
                    .endDate(LocalDate.of(2024, 12, 31))
                    .current(false)
                    .build();

            when(primaryCareService.getCurrentPrimaryCare(patientId))
                    .thenReturn(Optional.of(currentPcp));
            when(primaryCareService.getPrimaryCareHistory(patientId))
                    .thenReturn(List.of(currentPcp, pastPcp));

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result).isNotNull();
            assertThat(result.getPrimaryCare()).isNotNull();
            assertThat(result.getPrimaryCare().getDoctorDisplay()).isEqualTo("Dr. Smith");
            assertThat(result.getPrimaryCare().isCurrent()).isTrue();
            assertThat(result.getPrimaryCareHistory()).hasSize(2);
        }

        @Test
        @DisplayName("D115 — the clinicians of open visits are on the care team, once each, newest visit first")
        void getCareTeam_openVisitClinicians() {
            stubPatientResolution();
            when(primaryCareService.getCurrentPrimaryCare(patientId)).thenReturn(Optional.empty());
            when(primaryCareService.getPrimaryCareHistory(patientId)).thenReturn(List.of());

            com.example.hms.model.User doctorUser = new com.example.hms.model.User();
            doctorUser.setId(UUID.randomUUID());
            doctorUser.setFirstName("Awa");
            doctorUser.setLastName("Kaboré");
            doctorUser.addRole(role("ROLE_DOCTOR"));
            com.example.hms.model.Staff doctor = new com.example.hms.model.Staff();
            doctor.setUser(doctorUser);
            com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
            hospital.setId(UUID.randomUUID());
            hospital.setName("CHU Nord");
            com.example.hms.model.Encounter newer = new com.example.hms.model.Encounter();
            newer.setStaff(doctor);
            newer.setHospital(hospital);
            com.example.hms.model.Encounter older = new com.example.hms.model.Encounter();
            older.setStaff(doctor);
            older.setHospital(hospital);
            when(encounterRepository.findOpenWithClinicianByPatientId(org.mockito.ArgumentMatchers.eq(patientId),
                    org.mockito.ArgumentMatchers.argThat(closed ->
                            closed.contains(com.example.hms.enums.EncounterStatus.COMPLETED)
                                    && closed.contains(com.example.hms.enums.EncounterStatus.CANCELLED))))
                    .thenReturn(List.of(newer, older));

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getVisitClinicians()).singleElement().satisfies(entry -> {
                assertThat(entry.getUserId()).isEqualTo(doctorUser.getId());
                assertThat(entry.getDisplayName()).isEqualTo("Awa Kaboré");
                assertThat(entry.getHospitalName()).isEqualTo("CHU Nord");
            });
        }

        @Test
        @DisplayName("D115 — a deactivated account or a non-clinical role never reaches the care team (the send would be refused)")
        void getCareTeam_skipsUnreachableClinicians() {
            stubPatientResolution();
            when(primaryCareService.getCurrentPrimaryCare(patientId)).thenReturn(Optional.empty());
            when(primaryCareService.getPrimaryCareHistory(patientId)).thenReturn(List.of());

            com.example.hms.model.User inactive = visitUser("ROLE_DOCTOR");
            inactive.setActive(false);
            com.example.hms.model.User receptionist = visitUser("ROLE_RECEPTIONIST");
            com.example.hms.model.User nurse = visitUser("NURSE");
            when(encounterRepository.findOpenWithClinicianByPatientId(org.mockito.ArgumentMatchers.eq(patientId),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of(visit(inactive), visit(receptionist), visit(nurse)));

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getVisitClinicians()).singleElement()
                    .satisfies(entry -> assertThat(entry.getUserId()).isEqualTo(nurse.getId()));
        }

        private com.example.hms.model.Role role(String name) {
            com.example.hms.model.Role role = new com.example.hms.model.Role();
            role.setId(UUID.randomUUID());
            role.setName(name);
            return role;
        }

        private com.example.hms.model.User visitUser(String roleName) {
            com.example.hms.model.User visitor = new com.example.hms.model.User();
            visitor.setId(UUID.randomUUID());
            visitor.addRole(role(roleName));
            return visitor;
        }

        private com.example.hms.model.Encounter visit(com.example.hms.model.User user) {
            com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
            staff.setUser(user);
            com.example.hms.model.Encounter encounter = new com.example.hms.model.Encounter();
            encounter.setStaff(staff);
            return encounter;
        }

        @Test
        @DisplayName("should return null primaryCare when no current PCP exists")
        void getCareTeam_noCurrentPcp() {
            stubPatientResolution();
            when(primaryCareService.getCurrentPrimaryCare(patientId))
                    .thenReturn(Optional.empty());
            when(primaryCareService.getPrimaryCareHistory(patientId))
                    .thenReturn(List.of());

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getPrimaryCare()).isNull();
            assertThat(result.getPrimaryCareHistory()).isEmpty();
        }

        @Test
        @DisplayName("names the hospital of each entry, with one lookup")
        void getCareTeam_namesTheHospitals() {
            stubPatientResolution();
            UUID hospitalA = UUID.randomUUID();
            com.example.hms.model.Hospital a = new com.example.hms.model.Hospital();
            a.setId(hospitalA);
            a.setName("CHU Yalgado");
            PatientPrimaryCareResponseDTO currentPcp = PatientPrimaryCareResponseDTO.builder()
                    .id(UUID.randomUUID()).hospitalId(hospitalA).doctorDisplay("Dr. Smith").current(true).build();
            PatientPrimaryCareResponseDTO noHospital = PatientPrimaryCareResponseDTO.builder()
                    .id(UUID.randomUUID()).doctorDisplay("Dr. Jones").current(false).build();
            when(primaryCareService.getCurrentPrimaryCare(patientId)).thenReturn(Optional.of(currentPcp));
            when(primaryCareService.getPrimaryCareHistory(patientId)).thenReturn(List.of(currentPcp, noHospital));
            when(hospitalRepository.findAllById(java.util.Set.of(hospitalA))).thenReturn(List.of(a));

            CareTeamDTO result = service.getMyCareTeam(auth);

            assertThat(result.getPrimaryCare().getHospitalName()).isEqualTo("CHU Yalgado");
            assertThat(result.getPrimaryCareHistory().get(0).getHospitalName()).isEqualTo("CHU Yalgado");
            assertThat(result.getPrimaryCareHistory().get(1).getHospitalName()).isNull();
            verify(hospitalRepository, times(1)).findAllById(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Access Log
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAccessLog")
    class MyAccessLog {

        @Test
        @DisplayName("should return paginated access entries for the resolved patient")
        void getAccessLog_success() {
            stubPatientResolution();

            AccessLogEntryDTO expected = AccessLogEntryDTO.builder()
                    .id(UUID.randomUUID())
                    .actor("nurse.mary")
                    .actorRole("Nurse")
                    .hospitalName("City Clinic")
                    .eventType("PATIENT_ACCESS")
                    .entityType("PATIENT")
                    .resourceId(patientId.toString())
                    .description("Viewed patient vitals")
                    .status("SUCCESS")
                    .timestamp(LocalDateTime.of(2026, 2, 10, 14, 30))
                    .category(DisclosureCategory.TREATMENT_ACCESS)
                    .build();

            Pageable pageable = PageRequest.of(0, 20);
            when(disclosureAccountingService.getEntriesAsPatientSees(patientId, null, null, pageable))
                    .thenReturn(new PageImpl<>(List.of(expected), pageable, 1));

            Page<AccessLogEntryDTO> result = service.getMyAccessLog(auth, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            AccessLogEntryDTO entry = result.getContent().get(0);
            assertThat(entry.getActor()).isEqualTo("nurse.mary");
            assertThat(entry.getCategory()).isEqualTo(DisclosureCategory.TREATMENT_ACCESS);
            assertThat(entry.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 2, 10, 14, 30));
        }

        @Test
        @DisplayName("asks the disclosure service, not the entityType/resourceId convention")
        void getAccessLog_usesThePatientKey() {
            // Tier 2 item 39. This used to call
            // getAuditLogsByTarget("PATIENT", patientId) -- a convention that
            // break-the-glass and eligibility rows do not follow, so the
            // patient's own "who viewed my records" page omitted every
            // emergency override and every disclosure to an insurer.
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 20);
            when(disclosureAccountingService.getEntriesAsPatientSees(patientId, null, null, pageable))
                    .thenReturn(Page.empty(pageable));

            service.getMyAccessLog(auth, pageable);

            verify(disclosureAccountingService).getEntriesAsPatientSees(patientId, null, null, pageable);
            // No "never called the old query" assertion here: the service no
            // longer holds an AuditEventLogService at all, so the compiler
            // enforces that far harder than a mock verification could.
        }

        @Test
        @DisplayName("should return empty page when no audit events")
        void getAccessLog_empty() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 20);
            when(disclosureAccountingService.getEntriesAsPatientSees(patientId, null, null, pageable))
                    .thenReturn(Page.empty(pageable));

            Page<AccessLogEntryDTO> result = service.getMyAccessLog(auth, pageable);

            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("accounting resolves the patient from the JWT and passes the window through")
        void getAccounting_resolvesPatientAndWindow() {
            // The patient id never comes from the request on this surface --
            // that is what stops one patient reading another's accounting.
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 20);
            LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2026, 8, 1, 0, 0);
            DisclosureAccountingDTO expected =
                    DisclosureAccountingDTO.builder().totalEvents(4).build();
            when(disclosureAccountingService.getAccountingAsPatientSees(patientId, from, to, pageable))
                    .thenReturn(expected);

            DisclosureAccountingDTO result =
                    service.getMyDisclosureAccounting(auth, from, to, pageable);

            assertThat(result).isSameAs(expected);
            verify(disclosureAccountingService).getAccountingAsPatientSees(patientId, from, to, pageable);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Identity Resolution Edge Cases
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("identity resolution edge cases")
    class IdentityResolution {

        @Test
        @DisplayName("should throw BusinessException when user cannot be resolved from auth")
        void unresolvableUser_throwsBusiness() {
            when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(UUID.randomUUID()).build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unable to resolve user");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when no patient record linked")
        void noPatientRecord_throwsNotFound() {
            when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());

            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(UUID.randomUUID()).build();

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "patient.portal.noRecord");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Schedule Own Appointment (Self-Booking)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scheduleMyAppointment")
    class ScheduleMyAppointment {

        private UUID hospitalId;
        private UUID departmentId;
        private UUID staffId;
        private Hospital hospital;
        private Department department;
        private Staff staff;
        private UserRoleHospitalAssignment assignment;

        @BeforeEach
        void setUpScheduling() {
            hospitalId = UUID.randomUUID();
            departmentId = UUID.randomUUID();
            staffId = UUID.randomUUID();

            hospital = new Hospital();
            hospital.setId(hospitalId);
            hospital.setName("City General");
            hospital.setAddress("123 Main St");

            department = new Department();
            department.setId(departmentId);
            department.setName("Cardiology");
            department.setHospital(hospital);

            User staffUser = new User();
            staffUser.setId(UUID.randomUUID());
            staffUser.setFirstName("John");
            staffUser.setLastName("Smith");

            Role role = new Role();
            role.setCode("ROLE_DOCTOR");
            role.setName("Doctor");

            assignment = new UserRoleHospitalAssignment();
            assignment.setId(UUID.randomUUID());
            assignment.setRole(role);

            staff = new Staff();
            staff.setId(staffId);
            staff.setActive(true);
            staff.setHospital(hospital);
            staff.setDepartment(department);
            staff.setUser(staffUser);
            staff.setAssignment(assignment);
        }

        private void stubSchedulingBasics() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
                    .thenReturn(Optional.of(new PatientHospitalRegistration()));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        }

        @Test
        @DisplayName("should schedule appointment with explicit staff pick")
        void scheduleWithExplicitStaff_success() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any()))
                    .thenReturn(List.of());
            when(assignmentRepository.findActiveStaffAssignment(staff.getUser().getId(), hospitalId))
                    .thenReturn(Optional.of(assignment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
                Appointment a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AppointmentResponseDTO expectedDto = AppointmentResponseDTO.builder()
                    .id(UUID.randomUUID()).build();
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(expectedDto);

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .reason("Annual checkup")
                    .notes("First visit")
                    .build();

            AppointmentResponseDTO result = service.scheduleMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(result).isNotNull();
            // The reason a patient types is part of the visit, not just the notes:
            // it used to be dropped on the floor while notes were kept.
            ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(saved.capture());
            assertThat(saved.getValue().getReason()).isEqualTo("Annual checkup");
            assertThat(saved.getValue().getNotes()).isEqualTo("First visit");
        }

        @Test
        @DisplayName("should schedule appointment with auto-assigned provider when staffId is null")
        void scheduleWithAutoAssign_success() {
            stubSchedulingBasics();
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId))
                    .thenReturn(List.of(staff));
            // QA D1093: "any clinician" is the one whose published slot starts then.
            when(slotPolicy.offerableAt(departmentId, java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 0))))
                    .thenReturn(List.of(com.example.hms.model.scheduling.AppointmentSlot.builder()
                            .staff(staff)
                            .startAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 0)))
                            .endAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 30)))
                            .build()));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any()))
                    .thenReturn(List.of());
            when(assignmentRepository.findActiveStaffAssignment(staff.getUser().getId(), hospitalId))
                    .thenReturn(Optional.of(assignment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
                Appointment a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().id(UUID.randomUUID()).build());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(null)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            AppointmentResponseDTO result = service.scheduleMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(result).isNotNull();
            verify(staffRepository).findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId);
        }

        @Test
        @DisplayName("should default endTime to the slot rules' 30-minute fallback when no template covers it")
        void scheduleDefaultEndTime() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any()))
                    .thenReturn(List.of());
            when(assignmentRepository.findActiveStaffAssignment(staff.getUser().getId(), hospitalId))
                    .thenReturn(Optional.of(assignment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any()))
                    .thenReturn(AppointmentResponseDTO.builder().build());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(14, 0))
                    .endTime(null) // should default to 14:30
                    .build();

            service.scheduleMyAppointment(auth, dto, Locale.ENGLISH);

            ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(captor.capture());
            assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(14, 30));
        }

        @Test
        @DisplayName("should throw when patient is not registered at hospital")
        void notRegistered_throws() {
            stubPatientResolution();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
                    .thenReturn(Optional.empty());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                            .containsAnyOf("portal.booking.notRegistered", "not registered"));
        }

        @Test
        @DisplayName("should throw when department does not belong to hospital")
        void departmentWrongHospital_throws() {
            stubSchedulingBasics();
            Hospital otherHospital = new Hospital();
            otherHospital.setId(UUID.randomUUID());
            department.setHospital(otherHospital); // mismatch

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                            .containsAnyOf("portal.booking.departmentOtherHospital", "does not belong"));
        }

        @Test
        @DisplayName("should throw when provider does not belong to hospital")
        void staffWrongHospital_throws() {
            stubSchedulingBasics();
            Hospital otherHospital = new Hospital();
            otherHospital.setId(UUID.randomUUID());
            staff.setHospital(otherHospital); // mismatch

            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                            .containsAnyOf("portal.booking.providerOtherHospital", "does not work"));
        }

        @Test
        @DisplayName("should throw when provider is not available")
        void staffNotAvailable_throws() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(false);

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                            .containsAnyOf("portal.booking.providerUnavailable", "not available"));
        }

        @Test
        @DisplayName("should throw when time slot has overlap with existing appointment")
        void timeConflict_throws() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);

            // Create existing appointment at 10:00-10:30 that overlaps the request
            Appointment existing = new Appointment();
            existing.setAppointmentDate(BOOKING_DATE);
            existing.setStartTime(LocalTime.of(10, 0));
            existing.setEndTime(LocalTime.of(10, 30));
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any()))
                    .thenReturn(List.of(existing));

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 15))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                            .containsAnyOf("portal.booking.providerBusy", "already has an appointment"));
        }

        @Test
        @DisplayName("QA D1093: a time that is not a published, free slot is refused and nothing is booked")
        void d1093_anOffGridTimeIsRefused() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(slotPolicy.requirePublishedFreeSlot(eq(staffId), any(), eq(hospitalId), eq(departmentId)))
                    .thenThrow(new BusinessException("portal.booking.notAPublishedSlot"));

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId).departmentId(departmentId).staffId(staffId)
                    .date(BOOKING_DATE).startTime(LocalTime.of(10, 10)).build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
            verify(appointmentRepository, never()).save(any());
            verify(slotPolicy, never()).takeSlot(any(), any());
        }

        @Test
        @DisplayName("QA D1093: the self-booking takes its slot, so nobody else is offered it")
        void d1093_theBookingTakesItsSlot() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any())).thenReturn(List.of());
            when(assignmentRepository.findActiveStaffAssignment(staff.getUser().getId(), hospitalId))
                    .thenReturn(Optional.of(assignment));
            com.example.hms.model.scheduling.AppointmentSlot slot = com.example.hms.model.scheduling.AppointmentSlot
                    .builder().startAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 20)))
                    .endAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 40))).build();
            when(slotPolicy.requirePublishedFreeSlot(staffId,
                    java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 20)), hospitalId, departmentId))
                    .thenReturn(slot);
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(AppointmentResponseDTO.builder().build());

            service.scheduleMyAppointment(auth, PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId).departmentId(departmentId).staffId(staffId)
                    .date(BOOKING_DATE).startTime(LocalTime.of(10, 20)).build(), Locale.ENGLISH);

            ArgumentCaptor<Appointment> saved = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(saved.capture());
            assertThat(saved.getValue().getEndTime()).isEqualTo(LocalTime.of(10, 40));
            verify(slotPolicy).takeSlot(slot, saved.getValue());
        }

        @Test
        @DisplayName("QA D1093: any clinician with no published slot at that time is refused")
        void d1093_anyClinicianWithoutASlotIsRefused() {
            stubSchedulingBasics();
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId))
                    .thenReturn(List.of(staff));
            when(slotPolicy.offerableAt(eq(departmentId), any())).thenReturn(List.of());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId).departmentId(departmentId)
                    .date(BOOKING_DATE).startTime(LocalTime.of(15, 0)).build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
            verify(appointmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("QA D1093: the slots offered are the published, free ones of the day inside the patient day")
        void d1093_openSlotsAreThePublishedFreeOnes() {
            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
                    .thenReturn(Optional.of(new PatientHospitalRegistration()));
            when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId))
                    .thenReturn(List.of(staff));
            java.util.function.BiFunction<Integer, Integer, com.example.hms.model.scheduling.AppointmentSlot> at =
                    (h, m) -> {
                        com.example.hms.model.scheduling.AppointmentSlot s = com.example.hms.model.scheduling
                                .AppointmentSlot.builder().staff(staff).slotDate(BOOKING_DATE)
                                .startAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(h, m)))
                                .endAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(h, m)).plusMinutes(20))
                                .build();
                        s.setId(UUID.randomUUID());
                        return s;
                    };
            com.example.hms.model.scheduling.AppointmentSlot early = at.apply(6, 0);    // before the patient day
            com.example.hms.model.scheduling.AppointmentSlot free = at.apply(10, 0);
            com.example.hms.model.scheduling.AppointmentSlot bookedOver = at.apply(10, 20);
            when(slotPolicy.openOn(eq(hospitalId), eq(departmentId), eq(null), eq(BOOKING_DATE), any(), any(Integer.class)))
                    .thenReturn(List.of(early, free, bookedOver));
            Appointment desk = new Appointment();
            desk.setAppointmentDate(BOOKING_DATE);
            desk.setStartTime(LocalTime.of(10, 20));
            desk.setEndTime(LocalTime.of(10, 40));
            desk.setStatus(AppointmentStatus.SCHEDULED);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(staffId, BOOKING_DATE)).thenReturn(List.of(desk));

            List<java.util.Map<String, Object>> slots =
                    service.getOpenSlots(auth, hospitalId, departmentId, null, BOOKING_DATE);

            assertThat(slots).extracting(m -> m.get("slotId")).containsExactly(free.getId());
            assertThat(slots.get(0)).containsEntry("startTime", LocalTime.of(10, 0))
                    .containsEntry("endTime", LocalTime.of(10, 20));
        }

        @Test
        @DisplayName("should throw when no providers available in department for auto-assign")
        void noProviders_throws() {
            stubSchedulingBasics();
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId))
                    .thenReturn(List.of());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(null)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("QA D1060: a self-booking of a whole day takes one slot; the patient's end time is ignored")
        void aWholeDayRequestTakesTheSlotLength() {
            // Was "should throw when end time is not after start time": the API
            // accepted 07:00-19:00 for one patient; the end is no longer theirs.
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            when(appointmentRepository.findByStaff_IdAndAppointmentDate(eq(staffId), any())).thenReturn(List.of());
            when(assignmentRepository.findActiveStaffAssignment(staff.getUser().getId(), hospitalId))
                    .thenReturn(Optional.of(assignment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(AppointmentResponseDTO.builder().build());
            // QA D1093: the length is the published slot's own (a 20-minute slot).
            when(slotPolicy.requirePublishedFreeSlot(staffId,
                    java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 0)), hospitalId, departmentId))
                    .thenReturn(com.example.hms.model.scheduling.AppointmentSlot.builder()
                            .startAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 0)))
                            .endAt(java.time.LocalDateTime.of(BOOKING_DATE, LocalTime.of(10, 20)))
                            .build());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(19, 0))
                    .build();

            service.scheduleMyAppointment(auth, dto, Locale.ENGLISH);

            ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentRepository).save(captor.capture());
            assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(10, 20));
        }

        @Test
        @DisplayName("QA D1063: a self-booking over a slot held for a waitlist offer is refused")
        void aHeldSlotIsNotSelfBooked() {
            stubSchedulingBasics();
            when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
            when(staffAvailabilityService.isStaffAvailable(eq(staffId), any())).thenReturn(true);
            doThrow(new BusinessException("slot.heldForOffer"))
                    .when(slotPolicy).requireNoLiveHold(eq(staffId), any(), any());

            PortalBookAppointmentRequestDTO dto = PortalBookAppointmentRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .departmentId(departmentId)
                    .staffId(staffId)
                    .date(BOOKING_DATE)
                    .startTime(LocalTime.of(8, 0))
                    .build();

            assertThatThrownBy(() -> service.scheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "slot.heldForOffer");
            verify(appointmentRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scheduling Lookups (hospitals, departments, providers)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scheduling lookup endpoints")
    class SchedulingLookups {

        @Test
        @DisplayName("getMyHospitals — should return only active registrations")
        void getMyHospitals_returnsActiveRegistrations() {
            stubPatientResolution();

            Hospital h1 = new Hospital();
            h1.setId(UUID.randomUUID());
            h1.setName("City General");
            h1.setAddress("123 Main St");

            PatientHospitalRegistration reg = new PatientHospitalRegistration();
            reg.setHospital(h1);
            reg.setActive(true);

            PatientHospitalRegistration inactiveReg = new PatientHospitalRegistration();
            inactiveReg.setActive(false);
            Hospital h2 = new Hospital();
            h2.setId(UUID.randomUUID());
            h2.setName("Old Hospital");
            inactiveReg.setHospital(h2);

            when(registrationRepository.findByPatientId(patientId))
                    .thenReturn(List.of(reg, inactiveReg));

            List<java.util.Map<String, Object>> result = service.getMyHospitals(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("name", "City General");
        }

        @Test
        @DisplayName("getMyHospitals — empty when no registrations")
        void getMyHospitals_emptyWhenNoRegistrations() {
            stubPatientResolution();
            when(registrationRepository.findByPatientId(patientId))
                    .thenReturn(List.of());

            List<java.util.Map<String, Object>> result = service.getMyHospitals(auth);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("QA D128: departments of a hospital the patient is not registered at are not listed")
        void getDepartments_refusesAnUnregisteredHospital() {
            UUID hospId = UUID.randomUUID();
            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospId))
                    .thenReturn(java.util.Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getDepartmentsForHospital(auth, hospId))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
            org.mockito.Mockito.verifyNoInteractions(departmentRepository);
        }

        @Test
        @DisplayName("getDepartmentsForHospital — should map department fields correctly")
        void getDepartments_mapsDepartmentFields() {
            UUID hospId = UUID.randomUUID();
            Department d1 = new Department();
            d1.setId(UUID.randomUUID());
            d1.setName("Cardiology");

            Department d2 = new Department();
            d2.setId(UUID.randomUUID());
            d2.setName("Radiology");

            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospId))
                    .thenReturn(java.util.Optional.of(new com.example.hms.model.PatientHospitalRegistration()));
            when(departmentRepository.findByHospitalId(hospId)).thenReturn(List.of(d1, d2));
            when(staffRepository.findDepartmentIdsWithActiveProviders(hospId))
                    .thenReturn(java.util.Set.of(d1.getId(), d2.getId()));

            List<java.util.Map<String, Object>> result =
                    service.getDepartmentsForHospital(auth, hospId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "Cardiology");
            assertThat(result.get(1)).containsEntry("name", "Radiology");
        }

        @Test
        @DisplayName("getDepartmentsForHospital — QA D169: a department with no bookable provider is not offered")
        void getDepartments_hidesDepartmentsWithNoProvider() {
            UUID hospId = UUID.randomUUID();
            Department maternity = new Department();
            maternity.setId(UUID.randomUUID());
            maternity.setName("Maternite");
            Department empty = new Department();
            empty.setId(UUID.randomUUID());
            empty.setName("Empty");

            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospId))
                    .thenReturn(java.util.Optional.of(new com.example.hms.model.PatientHospitalRegistration()));
            when(departmentRepository.findByHospitalId(hospId)).thenReturn(List.of(maternity, empty));
            when(staffRepository.findDepartmentIdsWithActiveProviders(hospId))
                    .thenReturn(java.util.Set.of(maternity.getId()));

            List<java.util.Map<String, Object>> result =
                    service.getDepartmentsForHospital(auth, hospId);

            assertThat(result).extracting(m -> m.get("name")).containsExactly("Maternite");
        }

        @Test
        @DisplayName("getProvidersForDepartment — should map provider fields with fullName and a bare role token")
        void getProviders_mapsProviderFields() {
            UUID hospId = UUID.randomUUID();
            UUID deptId = UUID.randomUUID();

            User staffUser = new User();
            staffUser.setFirstName("Jane");
            staffUser.setLastName("Doe");

            // security.roles.name carries the prefix; the picker keys on the bare token.
            Role role = new Role();
            role.setName("ROLE_DOCTOR");

            UserRoleHospitalAssignment assign = new UserRoleHospitalAssignment();
            assign.setRole(role);

            Staff s = new Staff();
            s.setId(UUID.randomUUID());
            s.setName("Dr. Doe");
            s.setUser(staffUser);
            s.setAssignment(assign);

            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospId))
                    .thenReturn(java.util.Optional.of(new com.example.hms.model.PatientHospitalRegistration()));
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospId, deptId))
                    .thenReturn(List.of(s));

            List<java.util.Map<String, Object>> result =
                    service.getProvidersForDepartment(auth, hospId, deptId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("id", s.getId());
            assertThat(result.get(0)).containsEntry("name", "Dr. Doe");
            assertThat(result.get(0)).containsEntry("fullName", "Jane Doe");
            assertThat(result.get(0)).containsEntry("role", "DOCTOR");
        }

        @Test
        @DisplayName("getProvidersForDepartment — empty when no active staff")
        void getProviders_emptyWhenNone() {
            UUID hospId = UUID.randomUUID();
            UUID deptId = UUID.randomUUID();
            stubPatientResolution();
            when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospId))
                    .thenReturn(java.util.Optional.of(new com.example.hms.model.PatientHospitalRegistration()));
            when(staffRepository.findActiveProvidersByHospitalAndDepartment(hospId, deptId))
                    .thenReturn(List.of());

            List<java.util.Map<String, Object>> result =
                    service.getProvidersForDepartment(auth, hospId, deptId);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("portal invoice detail (QA D982)")
    class PortalInvoiceDetail {
        @Test
        void theDetailThePdfAndTheReceiptAreThePatientsOwn() {
            stubPatientResolution();
            UUID invoiceId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();

            service.getMyInvoiceDetail(auth, invoiceId);
            service.getMyInvoicePdf(auth, invoiceId, java.util.Locale.FRENCH);
            service.getMyReceiptPdf(auth, invoiceId, paymentId, java.util.Locale.FRENCH);

            verify(billingInvoiceService).getPortalInvoiceDetail(invoiceId, patientId);
            verify(billingInvoiceService).getPortalInvoicePdf(invoiceId, patientId, java.util.Locale.FRENCH);
            verify(billingInvoiceService).getPortalReceiptPdf(invoiceId, paymentId, patientId, java.util.Locale.FRENCH);
        }
    }
}
