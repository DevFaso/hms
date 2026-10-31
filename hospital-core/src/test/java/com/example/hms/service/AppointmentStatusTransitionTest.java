package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentStatusTransitionTest {

    @Mock
    private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private MessageSource messageSource;
    @Mock private UserRepository userRepository;
    @Mock private StaffAvailabilityService staffAvailabilityService;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EmailService emailService;
    @Mock private com.example.hms.service.scheduling.SlotInventoryService slotInventoryService;
    @Mock private com.example.hms.service.webhook.WebhookPublisher webhookPublisher;
    @Mock private com.example.hms.config.AppointmentLinkProperties appointmentLinks;
    @Mock private com.example.hms.service.i18n.PatientLocaleResolver patientLocaleResolver;
    @Mock private com.example.hms.repository.EncounterRepository encounterRepository;

    /** QA D1060: the slot rules, stubbed to their 30-minute fallback (SlotPolicyStubs). */
    @org.mockito.Mock
    private com.example.hms.service.scheduling.AppointmentSlotPolicy slotPolicy;

    @InjectMocks
    private AppointmentServiceImpl appointmentService;

    private User user;
    private Hospital hospital;
    private Appointment appointment;
    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        com.example.hms.testsupport.SlotPolicyStubs.defaultLength(slotPolicy);
        ReflectionTestUtils.setField(appointmentService, "frontendBaseUrl", "http://localhost");

        UUID hospitalId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();

        // Super-admin role so requireHospitalScope() is always satisfied
        UUID roleId = UUID.randomUUID();
        Role superAdminRole = Role.builder().name("ROLE_SUPER_ADMIN").code("ROLE_SUPER_ADMIN").build();
        superAdminRole.setId(roleId);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("doctor_b");

        UserRole userRole = UserRole.builder()
            .id(new UserRoleId(user.getId(), roleId))
            .user(user)
            .role(superAdminRole)
            .build();
        user.setUserRoles(Set.of(userRole));

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Hospital B");
        hospital.setEmail("h@b.com");
        hospital.setPhoneNumber("123");

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setEmail("jane@example.com");

        User staffUser = new User();
        staffUser.setFirstName("Dr.");
        staffUser.setLastName("Smith");

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);
        staff.setUser(staffUser);

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        assignment.setUser(user);

        appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setHospital(hospital);
        appointment.setPatient(patient);
        appointment.setStaff(staff);
        appointment.setAssignment(assignment);

        when(userRepository.findByUsername("doctor_b")).thenReturn(Optional.of(user));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
    }

    // ── Temporal guard: cannot complete a future appointment ──

    @Test
    void completeBlockedWhenAppointmentIsInTheFuture() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.status.notStartedYet"));

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void noShowBlockedWhenAppointmentIsInTheFuture() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "no_show", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.status.notStartedYet"));

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void completeAllowedWhenAppointmentStartTimeHasPassed() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        // Use yesterday's date to avoid midnight-boundary flakiness
        // (when LocalTime.now().minusMinutes(30) wraps past midnight)
        appointment.setAppointmentDate(LocalDate.now().minusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);

        appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b");

        verify(appointmentRepository).save(appointment);
        // Completion is not an outward-facing event - no webhook.
        org.mockito.Mockito.verifyNoInteractions(webhookPublisher);
    }

    // ── Invalid status transitions ──

    @Test
    void cannotCompleteFromScheduledStatus() {
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setStartTime(LocalTime.now().minusHours(1));
        appointment.setEndTime(LocalTime.now().plusHours(1));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED));

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void cannotCompleteAnAlreadyCompletedAppointment() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setAppointmentDate(LocalDate.now().minusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.COMPLETED, AppointmentStatus.COMPLETED));

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void cannotConfirmAnAlreadyCompletedAppointment() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setAppointmentDate(LocalDate.now().minusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "confirm", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.COMPLETED, AppointmentStatus.CONFIRMED));
    }

    @Test
    void cannotCancelAnAlreadyCancelledAppointment() {
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "cancel", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.CANCELLED, AppointmentStatus.CANCELLED));
    }

    // ── Valid transitions ──

    @Test
    void confirmFromScheduledIsAllowed() {
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);

        appointmentService.confirmOrCancelAppointment(appointmentId, "confirm", null, "doctor_b");

        verify(appointmentRepository).save(appointment);
        // Confirming keeps the appointment, so its slot must stay booked.
        verify(slotInventoryService, org.mockito.Mockito.never()).releaseForAppointment(any());
    }

    @Test
    void cancelFromConfirmedIsAllowed() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);

        appointmentService.confirmOrCancelAppointment(appointmentId, "cancel", null, "doctor_b");

        verify(appointmentRepository).save(appointment);
        // The appointment owns its slot (P3 #22): cancelling frees the time.
        verify(slotInventoryService).releaseForAppointment(appointmentId);
        // The fulfilment-critical link to item 45's outbox: the cancel is
        // announced to subscribers, keyed by this hospital and appointment.
        verify(webhookPublisher).publish(hospital.getId(),
            com.example.hms.enums.platform.WebhookEventType.APPOINTMENT_CANCELLED,
            "Appointment", appointmentId);
    }

    @Test
    void rescheduleActionEmitsTheRescheduledWebhook() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);

        appointmentService.confirmOrCancelAppointment(appointmentId, "reschedule", null, "doctor_b");

        verify(webhookPublisher).publish(hospital.getId(),
            com.example.hms.enums.platform.WebhookEventType.APPOINTMENT_RESCHEDULED,
            "Appointment", appointmentId);
    }

    @Test
    void confirmEmitsNoWebhook() {
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));

        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);

        appointmentService.confirmOrCancelAppointment(appointmentId, "confirm", null, "doctor_b");

        // Confirming changes nothing a subscriber tracks - no event.
        org.mockito.Mockito.verifyNoInteractions(webhookPublisher);
    }

    // ── The check-in / reschedule lifecycle holes ──

    /** Stub the save + map pair every happy-path transition needs. */
    private void stubSaveAndMap() {
        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentRepository.save(any())).thenReturn(appointment);
        when(appointmentMapper.toAppointmentResponseDTO(appointment)).thenReturn(dto);
    }

    private void givenAppointment(AppointmentStatus status, LocalDate date) {
        appointment.setStatus(status);
        appointment.setAppointmentDate(date);
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(10, 0));
    }

    @Test
    void checkedInAppointmentCanBeCompleted() {
        // CHECKED_IN had NO entry in the transition map at all.
        // ReceptionServiceImpl.checkInPatient sets the status directly on the
        // entity, bypassing the map, so check-in itself worked — but
        // getOrDefault then returned an empty set and EVERY later action was
        // refused. A checked-in appointment could never be completed. It sat
        // CHECKED_IN forever while the encounter carried the visit on alone.
        givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().minusDays(1));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b");

        verify(appointmentRepository).save(appointment);
        org.assertj.core.api.Assertions.assertThat(appointment.getStatus())
            .isEqualTo(AppointmentStatus.COMPLETED);
    }

    /** A visit opened for the appointment at check-in, in the given status. */
    private void givenVisit(com.example.hms.enums.EncounterStatus status) {
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setId(UUID.randomUUID());
        visit.setStatus(status);
        visit.setAppointment(appointment);
        when(encounterRepository.findByAppointmentIdIn(java.util.List.of(appointmentId)))
            .thenReturn(java.util.List.of(visit));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(value = com.example.hms.enums.EncounterStatus.class,
        names = {"ARRIVED", "TRIAGE", "WAITING_FOR_PHYSICIAN", "IN_PROGRESS", "AWAITING_RESULTS",
            "READY_FOR_DISCHARGE", "CANCELLED"}, mode = org.junit.jupiter.params.provider.EnumSource.Mode.INCLUDE)
    void qaD788_deskCannotCompleteTheAppointmentOfAVisitNotCheckedOut(com.example.hms.enums.EncounterStatus visit) {
        // QA D788: PUT /appointments/{id}/status?action=complete on a CHECKED_IN
        // appointment whose patient was never seen. The visit's check-out
        // completes the appointment; by hand, ahead of it, it is refused.
        givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().minusDays(1));
        givenVisit(visit);

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException refused = (BusinessException) ex;
                org.assertj.core.api.Assertions.assertThat(refused.getMessageKey())
                    .isEqualTo("appointment.complete.visitNotCompleted");
                org.assertj.core.api.Assertions.assertThat(refused.getArgs()).containsExactly(visit.name());
            });

        verify(appointmentRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    @Test
    void qaD788_theEditFormCannotCompleteTheAppointmentOfAnOpenVisitEither() {
        // PUT /appointments/{id} with status COMPLETED: the same refusal.
        givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().minusDays(1));
        givenVisit(com.example.hms.enums.EncounterStatus.WAITING_FOR_PHYSICIAN);
        com.example.hms.payload.dto.AppointmentRequestDTO edit = new com.example.hms.payload.dto.AppointmentRequestDTO();
        edit.setPatientId(UUID.randomUUID());
        edit.setStaffId(UUID.randomUUID());
        edit.setHospitalId(hospital.getId());
        edit.setAppointmentDate(appointment.getAppointmentDate());
        edit.setStartTime(appointment.getStartTime());
        edit.setEndTime(appointment.getEndTime());
        edit.setStatus(AppointmentStatus.COMPLETED);

        assertThatThrownBy(() ->
            appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.complete.visitNotCompleted"));

        verify(appointmentRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    @Test
    void qaD788_aCheckedOutVisitLetsTheAppointmentComplete() {
        givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().minusDays(1));
        givenVisit(com.example.hms.enums.EncounterStatus.COMPLETED);
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "complete", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void checkedInAppointmentCanBeMarkedNoShow() {
        // The patient checked in and then left before being seen. Rare, but
        // the front desk has to be able to record it.
        givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().minusDays(1));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "no_show", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus())
            .isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void confirmedAppointmentCanReturnToScheduled() {
        // Rescheduling a CONFIRMED appointment has to land somewhere the front
        // desk can act on, and CONFIRMED no longer tells the truth: the
        // patient agreed to a DIFFERENT time. SCHEDULED says "new time, not
        // yet confirmed", which is exactly the situation.
        givenAppointment(AppointmentStatus.CONFIRMED, LocalDate.now().plusDays(1));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "schedule", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus())
            .isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void rescheduledAppointmentCanBePutBackIntoPlay() {
        // The escape hatch for rows written before reschedule started landing
        // on SCHEDULED. The transition map always permitted
        // RESCHEDULED -> SCHEDULED; there was simply no action that performed
        // it, so those appointments had a date, a time, an expected patient,
        // and nothing anyone could do with them.
        givenAppointment(AppointmentStatus.RESCHEDULED, LocalDate.now());
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "schedule", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus())
            .isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void completedAppointmentCannotBeReopened() {
        // The widened map must not have opened terminal states. COMPLETED
        // still goes nowhere.
        givenAppointment(AppointmentStatus.COMPLETED, LocalDate.now().minusDays(1));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "schedule", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.COMPLETED, AppointmentStatus.SCHEDULED));

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void cancelledAppointmentCannotBeRevived() {
        givenAppointment(AppointmentStatus.CANCELLED, LocalDate.now().plusDays(1));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "schedule", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.CANCELLED, AppointmentStatus.SCHEDULED));

        verify(appointmentRepository, never()).save(any());
    }
    // ── QA D825: the refusal names both statuses as people read them ──

    @Test
    void qaD825_aRefusedStatusChangeReadsInTheCallersLanguage() {
        // The desk, in French, tried to confirm a COMPLETED appointment and read
        // « Cannot transition from COMPLETED to CONFIRMED »: English, and codes.
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            givenAppointment(AppointmentStatus.COMPLETED, LocalDate.now().minusDays(1));

            assertThatThrownBy(() ->
                appointmentService.confirmOrCancelAppointment(appointmentId, "confirm", java.util.Locale.FRENCH, "doctor_b"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Impossible de passer ce rendez-vous de « Terminé » à « Confirmé ».");

            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("es"));
            assertThatThrownBy(() ->
                appointmentService.confirmOrCancelAppointment(appointmentId, "confirm", null, "doctor_b"))
                .hasMessage("No se puede pasar esta cita de «Completada» a «Confirmada».");
        } finally {
            com.example.hms.utility.MessageUtil.setMessageSource(null);
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void qaD825_completingBeforeTheStartNamesTheStatusInTheCallersLanguage() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            givenAppointment(AppointmentStatus.CHECKED_IN, LocalDate.now().plusDays(1));

            assertThatThrownBy(() ->
                appointmentService.confirmOrCancelAppointment(appointmentId, "complete", java.util.Locale.FRENCH, "doctor_b"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException refused = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(refused.getMessageKey())
                        .isEqualTo("appointment.status.notStartedYet");
                    org.assertj.core.api.Assertions.assertThat(refused.getMessage())
                        .startsWith("Ce rendez-vous ne peut pas être marqué « Terminé » avant son heure de début prévue (")
                        .doesNotContain("COMPLETED")
                        .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T");
                });
        } finally {
            com.example.hms.utility.MessageUtil.setMessageSource(null);
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void qaD825_theEditFormRefusesAStatusChangeWithTheSameKey() {
        // PUT /appointments/{id} with a status the state machine refuses.
        givenAppointment(AppointmentStatus.COMPLETED, LocalDate.now().minusDays(1));
        Patient patient = appointment.getPatient();
        Staff staff = appointment.getStaff();
        when(patientRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(assignmentRepository.findActiveStaffAssignment(any(), any()))
            .thenReturn(Optional.of(appointment.getAssignment()));
        when(staffAvailabilityService.isStaffAvailable(any(), any())).thenReturn(true);
        com.example.hms.payload.dto.AppointmentRequestDTO edit = new com.example.hms.payload.dto.AppointmentRequestDTO();
        edit.setPatientId(patient.getId());
        edit.setStaffId(staff.getId());
        edit.setHospitalId(hospital.getId());
        edit.setAppointmentDate(appointment.getAppointmentDate());
        edit.setStartTime(appointment.getStartTime());
        edit.setEndTime(appointment.getEndTime());
        edit.setStatus(AppointmentStatus.CONFIRMED);

        assertThatThrownBy(() ->
            appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> refusedAsTransition(ex, AppointmentStatus.COMPLETED, AppointmentStatus.CONFIRMED));

        verify(appointmentRepository, never()).save(any());
    }

    // ── Review round 8 (QA D937/D946): freed slots and the PUT path ──

    private Appointment rebookedAtTheSameTime() {
        Appointment other = new Appointment();
        other.setId(UUID.randomUUID());
        other.setStaff(appointment.getStaff());
        other.setAppointmentDate(appointment.getAppointmentDate());
        other.setStartTime(LocalTime.of(9, 0));
        other.setEndTime(LocalTime.of(10, 0));
        other.setStatus(AppointmentStatus.SCHEDULED);
        return other;
    }

    @Test
    void round8_aMissedVisitWhoseTimeWasRebookedCannotBeParkedBackInIt() {
        // A no-show frees its slot (D937); B is booked there; parking A in
        // RESCHEDULED (a slot-holding status) would double-book the clinician.
        givenAppointment(AppointmentStatus.NO_SHOW, LocalDate.now().minusDays(1));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(
                appointment.getStaff().getId(), appointment.getAppointmentDate()))
            .thenReturn(java.util.List.of(appointment, rebookedAtTheSameTime()));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "reschedule", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.status.slotRebooked"));

        verify(appointmentRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void round8_aMissedVisitWhoseTimeIsStillFreeCanBeParked() {
        givenAppointment(AppointmentStatus.NO_SHOW, LocalDate.now().minusDays(1));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(
                appointment.getStaff().getId(), appointment.getAppointmentDate()))
            .thenReturn(java.util.List.of(appointment));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "reschedule", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
    }

    private com.example.hms.payload.dto.AppointmentRequestDTO editOf(AppointmentStatus status) {
        Patient patient = appointment.getPatient();
        Staff staff = appointment.getStaff();
        when(patientRepository.findById(patient.getId())).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(assignmentRepository.findActiveStaffAssignment(any(), any()))
            .thenReturn(Optional.of(appointment.getAssignment()));
        when(staffAvailabilityService.isStaffAvailable(any(), any())).thenReturn(true);
        com.example.hms.payload.dto.AppointmentRequestDTO edit = new com.example.hms.payload.dto.AppointmentRequestDTO();
        edit.setPatientId(patient.getId());
        edit.setStaffId(staff.getId());
        edit.setHospitalId(hospital.getId());
        edit.setAppointmentDate(appointment.getAppointmentDate());
        edit.setStartTime(appointment.getStartTime());
        edit.setEndTime(appointment.getEndTime());
        edit.setStatus(status);
        return edit;
    }

    @Test
    void round8_thePutCannotMarkAVisitThatHasNotStartedANoShow() {
        // SCHEDULED -> NO_SHOW is in the map (D946); the PUT used to skip the
        // temporal guard the status action applies.
        givenAppointment(AppointmentStatus.SCHEDULED, LocalDate.now().plusDays(7));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(AppointmentStatus.NO_SHOW);

        assertThatThrownBy(() ->
            appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.status.notStartedYet"));

        verify(appointmentRepository, never()).saveAndFlush(any());
        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void round8_aPutThatCancelsReleasesTheInventorySlotLikeTheStatusAction() {
        givenAppointment(AppointmentStatus.SCHEDULED, LocalDate.now().plusDays(7));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(AppointmentStatus.CANCELLED);
        ReflectionTestUtils.setField(appointmentService, "entityManager",
            mock(jakarta.persistence.EntityManager.class));
        org.mockito.Mockito.doAnswer(inv -> {
            appointment.setStatus(AppointmentStatus.CANCELLED);
            return null;
        }).when(appointmentMapper).updateAppointmentFromDto(any(), any(), any(), any(), any());
        when(appointmentRepository.saveAndFlush(any())).thenReturn(appointment);

        appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b");

        verify(slotInventoryService).releaseForAppointment(appointmentId);
    }

    @Test
    void round8_aPutThatOnlyEditsTheNotesKeepsTheInventorySlot() {
        givenAppointment(AppointmentStatus.SCHEDULED, LocalDate.now().plusDays(7));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(null);
        ReflectionTestUtils.setField(appointmentService, "entityManager",
            mock(jakarta.persistence.EntityManager.class));
        when(appointmentRepository.saveAndFlush(any())).thenReturn(appointment);

        appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b");

        verify(slotInventoryService, never()).releaseForAppointment(any());
    }

    @Test
    void round9_aMissedVisitWhoseTimeWasRebookedCanStillBeEdited() {
        // The no-show freed 09:00 and B was booked there. A note on A (status
        // unchanged, NO_SHOW) is not a conflict: A does not hold the slot.
        givenAppointment(AppointmentStatus.NO_SHOW, LocalDate.now().minusDays(1));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(null);
        org.mockito.Mockito.lenient().when(appointmentRepository.findByStaff_IdAndAppointmentDate(
                appointment.getStaff().getId(), appointment.getAppointmentDate()))
            .thenReturn(java.util.List.of(appointment, rebookedAtTheSameTime()));
        ReflectionTestUtils.setField(appointmentService, "entityManager",
            mock(jakarta.persistence.EntityManager.class));
        when(appointmentRepository.saveAndFlush(any())).thenReturn(appointment);

        appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b");

        verify(appointmentRepository).saveAndFlush(appointment);
    }

    @Test
    void round9_anEditThatKeepsTheVisitLiveStillRefusesARebookedTime() {
        givenAppointment(AppointmentStatus.SCHEDULED, LocalDate.now().plusDays(7));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(null);
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(
                appointment.getStaff().getId(), appointment.getAppointmentDate()))
            .thenReturn(java.util.List.of(appointment, rebookedAtTheSameTime()));
        org.mockito.Mockito.lenient().when(messageSource.getMessage(any(), any(), any(java.util.Locale.class)))
            .thenReturn("busy");

        assertThatThrownBy(() ->
            appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b"))
            .isInstanceOf(BusinessException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void round15_onePutCannotMoveAFutureVisitIntoThePastAndMarkItANoShow() {
        givenAppointment(AppointmentStatus.SCHEDULED, LocalDate.now().plusDays(1));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(AppointmentStatus.NO_SHOW);
        edit.setAppointmentDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() ->
            appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("appointment.status.notStartedYet"));
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void round15_aFailedVisitReopensTheRecallItAnswered() {
        com.example.hms.service.scheduling.PatientRecallService recalls =
            mock(com.example.hms.service.scheduling.PatientRecallService.class);
        appointmentService.setRecallService(recalls);
        givenAppointment(AppointmentStatus.IN_PROGRESS, LocalDate.now().minusDays(1));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "fail", null, "doctor_b");

        verify(recalls).reopenRecallsOf(appointmentId);
        verify(recalls, never()).completeRecallsOf(any());
    }

    @Test
    void round15_aNoteOnACompletedVisitDoesNotReCloseItsRecall() {
        com.example.hms.service.scheduling.PatientRecallService recalls =
            mock(com.example.hms.service.scheduling.PatientRecallService.class);
        appointmentService.setRecallService(recalls);
        givenAppointment(AppointmentStatus.COMPLETED, LocalDate.now().minusDays(1));
        com.example.hms.payload.dto.AppointmentRequestDTO edit = editOf(null);
        ReflectionTestUtils.setField(appointmentService, "entityManager", mock(jakarta.persistence.EntityManager.class));
        when(appointmentRepository.saveAndFlush(any())).thenReturn(appointment);

        appointmentService.updateAppointment(appointmentId, edit, java.util.Locale.ENGLISH, "doctor_b");

        org.mockito.Mockito.verifyNoInteractions(recalls);
    }

    @Test
    void round15_theStartIsJudgedOnTheHospitalsClock() {
        // A hospital 14 hours ahead of UTC: its visit started an hour ago on
        // its own clock, which on any server clock behind it is still "later".
        com.example.hms.model.Organization organization = new com.example.hms.model.Organization();
        organization.setDefaultTimezone("Pacific/Kiritimati");
        hospital.setOrganization(organization);
        java.time.LocalDateTime startedThere =
            java.time.LocalDateTime.now(java.time.ZoneId.of("Pacific/Kiritimati")).minusHours(1);
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setAppointmentDate(startedThere.toLocalDate());
        appointment.setStartTime(startedThere.toLocalTime());
        appointment.setEndTime(startedThere.toLocalTime().plusMinutes(1));
        stubSaveAndMap();

        appointmentService.confirmOrCancelAppointment(appointmentId, "no_show", null, "doctor_b");

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    private static void refusedAsTransition(Throwable ex, AppointmentStatus from, AppointmentStatus to) {
        BusinessException refused = (BusinessException) ex;
        org.assertj.core.api.Assertions.assertThat(refused.getMessageKey())
            .isEqualTo("appointment.status.invalidTransition");
        org.assertj.core.api.Assertions.assertThat(refused.getArgs())
            .containsExactly(AppointmentStatus.labelOf(from), AppointmentStatus.labelOf(to));
    }
}
