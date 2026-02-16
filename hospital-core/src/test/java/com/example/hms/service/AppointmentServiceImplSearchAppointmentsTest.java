package com.example.hms.service;

import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AppointmentFilterDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.specification.AppointmentSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceImplSearchAppointmentsTest {

    @Mock private EmailService emailService;
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

    @InjectMocks
    private AppointmentServiceImpl appointmentService;

    private static final String USERNAME = "doctor.test";
    private static final Locale DEFAULT_LOCALE = Locale.US;
    private Pageable pageable;

    @BeforeEach
    void configure() {
        pageable = PageRequest.of(0, 10);
    }

    @Test
    void searchAppointmentsForSuperAdminUsesBaseSpecificationOnly() {
        User user = userWithRoles("ROLE_SUPER_ADMIN");
    AppointmentFilterDTO filter = AppointmentFilterDTO.builder().search("test").build();
        when(userRepository.findByUsername(USERNAME)).thenReturn(java.util.Optional.of(user));

        Page<Appointment> appointments = new PageImpl<>(List.of(new Appointment()));
        when(appointmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(appointments);
        AppointmentResponseDTO responseDTO = AppointmentResponseDTO.builder().build();
        when(appointmentMapper.toAppointmentResponseDTO(any(Appointment.class))).thenReturn(responseDTO);

        try (MockedStatic<AppointmentSpecification> mocked = mockStatic(AppointmentSpecification.class)) {
            Specification<Appointment> baseSpec = (root, query, cb) -> null;
            mocked.when(() -> AppointmentSpecification.withFilter(filter)).thenReturn(baseSpec);

            Page<AppointmentResponseDTO> result = appointmentService.searchAppointments(filter, pageable, DEFAULT_LOCALE, USERNAME);

            assertThat(result.getContent()).containsExactly(responseDTO);
            mocked.verify(() -> AppointmentSpecification.withFilter(filter));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    void searchAppointmentsForPatientAddsPatientSpecification() {
        User user = userWithRoles("ROLE_PATIENT");
        when(userRepository.findByUsername(USERNAME)).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        Page<Appointment> appointments = new PageImpl<>(List.of(new Appointment()));
        when(appointmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(appointments);
        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().build();
        when(appointmentMapper.toAppointmentResponseDTO(any(Appointment.class))).thenReturn(dto);

        AppointmentFilterDTO filter = AppointmentFilterDTO.builder().build();

        try (MockedStatic<AppointmentSpecification> mocked = mockStatic(AppointmentSpecification.class)) {
            Specification<Appointment> baseSpec = (root, query, cb) -> null;
            Specification<Appointment> patientSpec = (root, query, cb) -> null;
            mocked.when(() -> AppointmentSpecification.withFilter(filter)).thenReturn(baseSpec);
            mocked.when(() -> AppointmentSpecification.forPatientUser(user.getId())).thenReturn(patientSpec);

            Page<AppointmentResponseDTO> result = appointmentService.searchAppointments(filter, pageable, DEFAULT_LOCALE, USERNAME);

            assertThat(result.getContent()).containsExactly(dto);
            mocked.verify(() -> AppointmentSpecification.withFilter(filter));
            mocked.verify(() -> AppointmentSpecification.forPatientUser(user.getId()));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    void searchAppointmentsWhenUserHasNoHospitalsReturnsEmptyPage() {
        User user = userWithRoles("ROLE_ADMIN");
        when(userRepository.findByUsername(USERNAME)).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        AppointmentFilterDTO filter = AppointmentFilterDTO.builder().build();

        try (MockedStatic<AppointmentSpecification> mocked = mockStatic(AppointmentSpecification.class)) {
            Specification<Appointment> baseSpec = (root, query, cb) -> null;
            mocked.when(() -> AppointmentSpecification.withFilter(filter)).thenReturn(baseSpec);

            Page<AppointmentResponseDTO> result = appointmentService.searchAppointments(filter, pageable, DEFAULT_LOCALE, USERNAME);

            assertThat(result.getContent()).isEmpty();
            verify(appointmentRepository, never()).findAll(any(Specification.class), eq(pageable));
            mocked.verify(() -> AppointmentSpecification.withFilter(filter));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    void searchAppointmentsForScopedUserAddsHospitalSpecification() {
        User user = userWithRoles("ROLE_DOCTOR");
        when(userRepository.findByUsername(USERNAME)).thenReturn(java.util.Optional.of(user));

        Hospital hospital = Hospital.builder().name("Scoped").code("SC").build();
        hospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .hospital(hospital)
            .active(Boolean.TRUE)
            .role(Role.builder().code("ROLE_DOCTOR").name("Doctor").build())
            .user(user)
            .build();
        assignment.setId(UUID.randomUUID());
        when(assignmentRepository.findAllByUserId(user.getId())).thenReturn(List.of(assignment));

        Page<Appointment> appointments = new PageImpl<>(List.of(new Appointment()));
        when(appointmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(appointments);
        AppointmentResponseDTO dto = AppointmentResponseDTO.builder().build();
        when(appointmentMapper.toAppointmentResponseDTO(any(Appointment.class))).thenReturn(dto);

        AppointmentFilterDTO filter = AppointmentFilterDTO.builder().build();

        try (MockedStatic<AppointmentSpecification> mocked = mockStatic(AppointmentSpecification.class)) {
            Specification<Appointment> baseSpec = (root, query, cb) -> null;
            Specification<Appointment> hospitalSpec = (root, query, cb) -> null;
            Set<UUID> allowedHospitals = Set.of(hospital.getId());
            mocked.when(() -> AppointmentSpecification.withFilter(filter)).thenReturn(baseSpec);
            mocked.when(() -> AppointmentSpecification.inHospitals(allowedHospitals)).thenReturn(hospitalSpec);

            Page<AppointmentResponseDTO> result = appointmentService.searchAppointments(filter, pageable, DEFAULT_LOCALE, USERNAME);

            assertThat(result.getContent()).containsExactly(dto);
            mocked.verify(() -> AppointmentSpecification.withFilter(filter));
            mocked.verify(() -> AppointmentSpecification.inHospitals(allowedHospitals));
            mocked.verifyNoMoreInteractions();
        }
    }

    private static User userWithRoles(String roleCode) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("user" + roleCode);
        user.setPasswordHash("hash");
        user.setEmail(roleCode.toLowerCase() + "@example.com");
        user.setPhoneNumber("0000000000");

        Role role = Role.builder().code(roleCode).name(roleCode).build();
        role.setId(UUID.randomUUID());

        UserRole link = UserRole.builder().role(role).build();
        user.setUserRoles(Set.of(link));

        return user;
    }
}
