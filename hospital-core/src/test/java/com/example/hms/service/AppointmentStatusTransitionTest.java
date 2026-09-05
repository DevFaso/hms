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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentStatusTransitionTest {

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

    @InjectMocks
    private AppointmentServiceImpl appointmentService;

    private User user;
    private Hospital hospital;
    private Appointment appointment;
    private UUID appointmentId;

    @BeforeEach
    void setUp() {
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
            .hasMessageContaining("before its scheduled start time");

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
            .hasMessageContaining("before its scheduled start time");

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
            .hasMessageContaining("Cannot transition from SCHEDULED to COMPLETED");

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
            .hasMessageContaining("Cannot transition from COMPLETED to COMPLETED");

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
            .hasMessageContaining("Cannot transition from COMPLETED to CONFIRMED");
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
            .hasMessageContaining("Cannot transition from CANCELLED");
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
            .hasMessageContaining("Cannot transition from COMPLETED to SCHEDULED");

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void cancelledAppointmentCannotBeRevived() {
        givenAppointment(AppointmentStatus.CANCELLED, LocalDate.now().plusDays(1));

        assertThatThrownBy(() ->
            appointmentService.confirmOrCancelAppointment(appointmentId, "schedule", null, "doctor_b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot transition from CANCELLED to SCHEDULED");

        verify(appointmentRepository, never()).save(any());
    }
}
