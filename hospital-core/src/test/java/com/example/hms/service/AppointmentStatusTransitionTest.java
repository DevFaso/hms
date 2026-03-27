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
    }
}
