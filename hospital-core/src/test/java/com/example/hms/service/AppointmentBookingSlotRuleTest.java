package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.scheduling.AppointmentSlotPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

/**
 * QA D1429 on the booking itself: staff pass the overbooking rule and the reason is kept and audited; a
 * patient on POST /appointments takes a published free slot, whatever they send. The rule's own cases are
 * in {@code AppointmentSlotPolicyOverbookTest}, the real endpoint in {@code FrontDeskRoundTripIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentBookingSlotRuleTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(30);
    private static final LocalTime TIME = LocalTime.of(9, 0);
    private static final LocalDateTime START = LocalDateTime.of(DAY, TIME);

    @Mock private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private StaffAvailabilityService staffAvailabilityService;
    @Mock private MessageSource messageSource;
    @Mock private AppointmentSlotPolicy slotPolicy;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.service.webhook.WebhookPublisher webhookPublisher;
    @Mock private com.example.hms.config.AppointmentLinkProperties appointmentLinks;

    @InjectMocks private AppointmentServiceImpl service;

    private final Hospital hospital = new Hospital();
    private final Department department = new Department();
    private final Patient patient = new Patient();
    private final Staff staff = new Staff();
    private User desk;
    private User patientUser;

    private static User user(String username, String roleCode) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setFirstName("A");
        user.setLastName("B");
        Role role = new Role();
        role.setCode(roleCode);
        UserRole link = new UserRole();
        link.setRole(role);
        user.setUserRoles(Set.of(link));
        return user;
    }

    @BeforeEach
    void setUp() {
        hospital.setId(UUID.randomUUID());
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);
        patient.setId(UUID.randomUUID());
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);
        staff.setUser(user("dr", "ROLE_DOCTOR"));
        desk = user("desk", "ROLE_RECEPTIONIST");
        patientUser = user("self", "ROLE_PATIENT");
        patient.setUser(patientUser);

        when(userRepository.findByUsername("desk")).thenReturn(Optional.of(desk));
        when(userRepository.findByUsername("self")).thenReturn(Optional.of(patientUser));
        UserRoleHospitalAssignment deskAssignment = new UserRoleHospitalAssignment();
        Role deskRole = new Role();
        deskRole.setCode("ROLE_RECEPTIONIST");
        deskAssignment.setUser(desk);
        deskAssignment.setRole(deskRole);
        deskAssignment.setHospital(hospital);
        deskAssignment.setActive(true);
        when(assignmentRepository.findAllByUserId(desk.getId())).thenReturn(List.of(deskAssignment));
        UserRoleHospitalAssignment patientAssignment = new UserRoleHospitalAssignment();
        Role patientRole = new Role();
        patientRole.setCode("ROLE_PATIENT");
        patientAssignment.setUser(patientUser);
        patientAssignment.setRole(patientRole);
        patientAssignment.setHospital(hospital);
        patientAssignment.setActive(true);
        when(assignmentRepository.findAllByUserId(patientUser.getId())).thenReturn(List.of(patientAssignment));
        when(assignmentRepository.findActiveStaffAssignment(any(), any()))
            .thenReturn(Optional.of(new UserRoleHospitalAssignment()));
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(patientRepository.existsByIdAndUserId(patient.getId(), patientUser.getId())).thenReturn(true);
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(department.getId())).thenReturn(Optional.of(department));
        when(staffAvailabilityService.isStaffAvailable(any(), any())).thenReturn(true);
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(any(), any())).thenReturn(List.of());
        when(slotPolicy.endFor(any(), any(), any())).thenReturn(TIME.plusMinutes(30));
        lenient().when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppointmentRequestDTO request(String overbookReason) {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setPatientId(patient.getId());
        request.setStaffId(staff.getId());
        request.setHospitalId(hospital.getId());
        request.setDepartmentId(department.getId());
        request.setAppointmentDate(DAY);
        request.setStartTime(TIME);
        request.setOverbookReason(overbookReason);
        return request;
    }

    @Test
    @DisplayName("staff off the slots: the reason the rule returns is kept on the visit, and audited without its text")
    void theDesksReasonIsKeptAndAudited() {
        when(slotPolicy.overbookReasonOffSlot(staff.getId(), START, hospital.getId(), "Urgence signalée"))
            .thenReturn("Urgence signalée");

        service.createAppointment(request("Urgence signalée"), Locale.FRENCH, "desk");

        verify(appointmentRepository).save(argThat(a -> "Urgence signalée".equals(a.getOverbookReason())));
        verify(auditEventLogService).logEvent(argThat(event -> event.getEventType() == AuditEventType.APPOINTMENT_CREATED
            && !event.getEventDescription().contains("Urgence")));
        verify(slotPolicy, never()).requirePublishedFreeSlot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("staff with no reason off the slots: the rule's refusal stands and nothing is booked")
    void theRefusalBooksNothing() {
        when(slotPolicy.overbookReasonOffSlot(staff.getId(), START, hospital.getId(), null))
            .thenThrow(new FieldBusinessException(AppointmentSlotPolicy.OVERBOOK_REASON_FIELD, "appointment.overbook.reasonRequired"));

        AppointmentRequestDTO request = request(null);
        assertThatThrownBy(() -> service.createAppointment(request, Locale.FRENCH, "desk"))
            .isInstanceOf(FieldBusinessException.class);

        verify(appointmentRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("staff on a free slot: no reason kept, no overbooking on the audit trail")
    void aSlotIsNoOverbooking() {
        when(slotPolicy.overbookReasonOffSlot(any(), any(), any(), any())).thenReturn(null);

        service.createAppointment(request(null), Locale.FRENCH, "desk");

        verify(appointmentRepository).save(argThat(a -> a.getOverbookReason() == null));
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a patient on POST /appointments is refused off the slots, and sending a reason changes nothing")
    void aPatientIsRefusedOffTheSlots() {
        when(slotPolicy.requirePublishedFreeSlot(staff.getId(), START, hospital.getId(), null))
            .thenThrow(new FieldBusinessException("startTime", "portal.booking.notAPublishedSlot"));

        AppointmentRequestDTO request = request("Je veux ce créneau");
        assertThatThrownBy(() -> service.createAppointment(request, Locale.FRENCH, "self"))
            .isInstanceOf(FieldBusinessException.class);

        verify(appointmentRepository, never()).save(any());
        verify(slotPolicy, never()).overbookReasonOffSlot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a patient cannot take a slot with a dead visit: whatever status is sent, the visit is live")
    void aPatientsVisitIsLive() {
        when(slotPolicy.requirePublishedFreeSlot(staff.getId(), START, hospital.getId(), null))
            .thenReturn(new AppointmentSlot());
        AppointmentRequestDTO request = request(null);
        request.setStatus(com.example.hms.enums.AppointmentStatus.CANCELLED);
        request.setStatusReason("x");

        service.createAppointment(request, Locale.FRENCH, "self");

        verify(appointmentRepository).save(argThat(
            a -> a.getStatus() == com.example.hms.enums.AppointmentStatus.SCHEDULED));
    }

    @Test
    @DisplayName("a patient cannot take a slot already past, as on the portal")
    void aPatientCannotBookThePast() {
        LocalDate past = LocalDate.now().minusDays(2);
        when(slotPolicy.requirePublishedFreeSlot(any(), any(), any(), any())).thenReturn(new AppointmentSlot());
        AppointmentRequestDTO request = request(null);
        request.setAppointmentDate(past);

        assertThatThrownBy(() -> service.createAppointment(request, Locale.FRENCH, "self"))
            .isInstanceOf(FieldBusinessException.class);

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a patient on POST /appointments takes the published slot, and no reason is kept")
    void aPatientTakesTheSlot() {
        AppointmentSlot slot = new AppointmentSlot();
        when(slotPolicy.requirePublishedFreeSlot(staff.getId(), START, hospital.getId(), null)).thenReturn(slot);

        service.createAppointment(request("Je veux ce créneau"), Locale.FRENCH, "self");

        verify(slotPolicy).takeSlot(eq(slot), any(Appointment.class));
        verify(appointmentRepository).save(argThat(a -> a.getOverbookReason() == null));
    }

    @Test
    @DisplayName("QA D1473: an overbook reason does not book a time already gone")
    void aTimeAlreadyGoneIsRefusedOnStartTime() {
        AppointmentRequestDTO gone = request("Urgence");
        gone.setAppointmentDate(LocalDate.now(java.time.ZoneId.systemDefault()).minusDays(1));

        assertThatThrownBy(() -> service.createAppointment(gone, Locale.FRENCH, "desk"))
            .isInstanceOfSatisfying(FieldBusinessException.class, refused -> {
                assertThat(refused.getField()).isEqualTo("startTime");
                assertThat(refused.getMessageKey()).isEqualTo("portal.booking.pastTime");
            });
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D1476: a deactivated registration is not booked")
    void aDeactivatedRegistrationIsNotBooked() {
        doThrow(new FieldBusinessException("patientId", "patient.registration.inactive"))
            .when(patientRegistrationGuard).refuseDeactivatedRegistration(patient.getId(), hospital.getId());

        AppointmentRequestDTO request = request(null);
        assertThatThrownBy(() -> service.createAppointment(request, Locale.FRENCH, "desk"))
            .isInstanceOf(FieldBusinessException.class);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D1474: the booking's own reason for a taken or past time, asked while typing; a free time has none")
    void theDeskIsToldAtOnce() {
        when(messageSource.getMessage(eq("appointment.staff.unavailable"), any(), any()))
            .thenReturn("TAKEN");
        when(messageSource.getMessage(eq("portal.booking.pastTime"), any(), any()))
            .thenReturn("PAST");

        assertThat(
            service.bookingTimeRefusal(staff.getId(), DAY, TIME, null, Locale.FRENCH, "desk")).isNull();

        LocalDate yesterday = LocalDate.now(java.time.ZoneId.systemDefault()).minusDays(1);
        assertThat(
            service.bookingTimeRefusal(staff.getId(), yesterday, TIME, null, Locale.FRENCH, "desk")).isEqualTo("PAST");

        com.example.hms.model.Appointment held = new com.example.hms.model.Appointment();
        held.setId(UUID.randomUUID());
        held.setStatus(com.example.hms.enums.AppointmentStatus.SCHEDULED);
        held.setAppointmentDate(DAY);
        held.setStartTime(TIME);
        held.setEndTime(TIME.plusMinutes(30));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(staff.getId(), DAY)).thenReturn(List.of(held));
        assertThat(
            service.bookingTimeRefusal(staff.getId(), DAY, TIME, null, Locale.FRENCH, "desk")).isEqualTo("TAKEN");
        // The visit being moved never conflicts with itself.
        assertThat(
            service.bookingTimeRefusal(staff.getId(), DAY, TIME, held.getId(), Locale.FRENCH, "desk")).isNull();
    }
}
