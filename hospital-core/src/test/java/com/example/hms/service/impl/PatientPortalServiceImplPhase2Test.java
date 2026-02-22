package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientRepository;
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
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Phase 2 patient portal service methods.
 * Tests cover: cancel/reschedule appointments, consent management,
 * home vitals, medication refills, after-visit summaries, care team, access log.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplPhase2Test {

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

    @InjectMocks
    private PatientPortalServiceImpl service;

    @Mock private Authentication auth;

    private UUID userId;
    private UUID patientId;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
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

            assertThatThrownBy(() -> service.cancelMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("does not belong to you");
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
                    .hasMessageContaining("already cancelled");
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
                    .hasMessageContaining("Cannot cancel a completed");
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
                    .newDate(LocalDate.of(2026, 5, 20))
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .reason("Need afternoon slot")
                    .build();

            AppointmentResponseDTO result = service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH);

            assertThat(result).isNotNull();
            assertThat(appointment.getAppointmentDate()).isEqualTo(LocalDate.of(2026, 5, 20));
            assertThat(appointment.getStartTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(appointment.getEndTime()).isEqualTo(LocalTime.of(14, 30));
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
            assertThat(appointment.getNotes()).contains("Patient rescheduled: Need afternoon slot");
            verify(appointmentRepository).save(appointment);
        }

        @Test
        @DisplayName("should throw when appointment not found")
        void notFound_throws() {
            stubPatientResolution();
            UUID apptId = UUID.randomUUID();
            when(appointmentRepository.findById(apptId)).thenReturn(Optional.empty());

            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(LocalDate.of(2026, 5, 20))
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
                    .newDate(LocalDate.of(2026, 5, 20))
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class);
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
                    .newDate(LocalDate.of(2026, 5, 20))
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Cannot reschedule a completed");
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
                    .newDate(LocalDate.of(2026, 5, 20))
                    .newStartTime(LocalTime.of(14, 0))
                    .newEndTime(LocalTime.of(14, 30))
                    .build();

            assertThatThrownBy(() -> service.rescheduleMyAppointment(auth, dto, Locale.ENGLISH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Cannot reschedule a cancelled");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Grant / Revoke Consent
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("consent management")
    class ConsentManagement {

        @Test
        @DisplayName("grantMyConsent — should auto-fill patientId and delegate")
        void grantConsent_autoFillsPatientId() {
            stubPatientResolution();
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();

            PortalConsentRequestDTO portalDto = PortalConsentRequestDTO.builder()
                    .fromHospitalId(from)
                    .toHospitalId(to)
                    .purpose("Treatment")
                    .build();

            PatientConsentResponseDTO expectedResponse = new PatientConsentResponseDTO();
            when(consentService.grantConsent(any(PatientConsentRequestDTO.class)))
                    .thenReturn(expectedResponse);

            PatientConsentResponseDTO result = service.grantMyConsent(auth, portalDto);

            assertThat(result).isEqualTo(expectedResponse);

            ArgumentCaptor<PatientConsentRequestDTO> captor =
                    ArgumentCaptor.forClass(PatientConsentRequestDTO.class);
            verify(consentService).grantConsent(captor.capture());

            PatientConsentRequestDTO captured = captor.getValue();
            assertThat(captured.getPatientId()).isEqualTo(patientId);
            assertThat(captured.getFromHospitalId()).isEqualTo(from);
            assertThat(captured.getToHospitalId()).isEqualTo(to);
            assertThat(captured.getPurpose()).isEqualTo("Treatment");
        }

        @Test
        @DisplayName("revokeMyConsent — should resolve patientId and delegate")
        void revokeConsent_delegatesToService() {
            stubPatientResolution();
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();

            service.revokeMyConsent(auth, from, to);

            verify(consentService).revokeConsent(patientId, from, to);
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
        @DisplayName("requestMedicationRefill — should throw if prescription not found")
        void requestRefill_prescriptionNotFound_throws() {
            stubPatientResolution();
            UUID rxId = UUID.randomUUID();
            when(prescriptionRepository.findById(rxId)).thenReturn(Optional.empty());

            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(rxId).build();

            assertThatThrownBy(() -> service.requestMedicationRefill(auth, dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Prescription not found");
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

            assertThatThrownBy(() -> service.requestMedicationRefill(auth, dto))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("do not have access to this prescription");
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

            assertThatThrownBy(() -> service.cancelMyRefill(auth, refillId))
                    .isInstanceOf(AccessDeniedException.class);
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
            when(dischargeSummaryService.getDischargeSummariesByPatient(patientId, Locale.ENGLISH))
                    .thenReturn(List.of(summary));

            List<DischargeSummaryResponseDTO> result =
                    service.getMyAfterVisitSummaries(auth, Locale.ENGLISH);

            assertThat(result).hasSize(1);
            verify(dischargeSummaryService).getDischargeSummariesByPatient(patientId, Locale.ENGLISH);
        }

        @Test
        @DisplayName("should return empty list when no summaries exist")
        void noSummaries_returnsEmpty() {
            stubPatientResolution();
            when(dischargeSummaryService.getDischargeSummariesByPatient(patientId, Locale.ENGLISH))
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
    }

    // ══════════════════════════════════════════════════════════════════════
    // Access Log
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMyAccessLog")
    class MyAccessLog {

        @Test
        @DisplayName("should return paginated audit entries mapped to AccessLogEntryDTO")
        void getAccessLog_success() {
            stubPatientResolution();

            AuditEventLogResponseDTO auditEntry = AuditEventLogResponseDTO.builder()
                    .userName("nurse.mary")
                    .eventType("VIEW")
                    .entityType("PATIENT")
                    .resourceId(patientId.toString())
                    .eventDescription("Viewed patient vitals")
                    .status("SUCCESS")
                    .eventTimestamp(LocalDateTime.of(2026, 2, 10, 14, 30))
                    .build();

            Pageable pageable = PageRequest.of(0, 20);
            Page<AuditEventLogResponseDTO> auditPage =
                    new PageImpl<>(List.of(auditEntry), pageable, 1);

            when(auditEventLogService.getAuditLogsByTarget("PATIENT", patientId.toString(), pageable))
                    .thenReturn(auditPage);

            Page<AccessLogEntryDTO> result = service.getMyAccessLog(auth, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            AccessLogEntryDTO entry = result.getContent().get(0);
            assertThat(entry.getActor()).isEqualTo("nurse.mary");
            assertThat(entry.getEventType()).isEqualTo("VIEW");
            assertThat(entry.getDescription()).isEqualTo("Viewed patient vitals");
            assertThat(entry.getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 2, 10, 14, 30));
        }

        @Test
        @DisplayName("should return empty page when no audit events")
        void getAccessLog_empty() {
            stubPatientResolution();
            Pageable pageable = PageRequest.of(0, 20);
            when(auditEventLogService.getAuditLogsByTarget("PATIENT", patientId.toString(), pageable))
                    .thenReturn(Page.empty(pageable));

            Page<AccessLogEntryDTO> result = service.getMyAccessLog(auth, pageable);

            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getContent()).isEmpty();
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
                    .hasMessageContaining("No patient record linked");
        }
    }
}
