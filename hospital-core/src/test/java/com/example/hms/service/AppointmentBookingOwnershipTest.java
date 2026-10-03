package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /appointments} for a pure patient: they book only for
 * themselves, and naming anybody else answers exactly as naming a patient that
 * does not exist — it used to be a 403 for a real patient beside a 404 for a
 * made-up one, which told a patient which ids, usernames and emails exist.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("POST /appointments: a patient naming someone else is answered as a miss")
class AppointmentBookingOwnershipTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private MessageSource messageSource;

    @InjectMocks private AppointmentServiceImpl service;

    private final Locale locale = Locale.ENGLISH;
    private User patientUser;
    private User receptionist;

    @BeforeEach
    void setUp() {
        patientUser = user("patient.self", "ROLE_PATIENT");
        receptionist = user("front.desk", "ROLE_RECEPTIONIST");
        when(userRepository.findByUsername(patientUser.getUsername())).thenReturn(Optional.of(patientUser));
        when(userRepository.findByUsername(receptionist.getUsername())).thenReturn(Optional.of(receptionist));
    }

    private static User user(String username, String roleCode) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        Role role = new Role();
        role.setCode(roleCode);
        UserRole link = new UserRole();
        link.setRole(role);
        user.setUserRoles(Set.of(link));
        return user;
    }

    private AppointmentRequestDTO forPatientId(UUID patientId) {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(UUID.randomUUID());
        return request;
    }

    private ResourceNotFoundException refusal(AppointmentRequestDTO request, User caller) {
        return catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.createAppointment(request, locale, caller.getUsername()));
    }

    @Test
    @DisplayName("another patient's id answers exactly as the unknown-id answer staff get, and is never loaded")
    void foreignPatientIdAnswersAsUnknown() {
        UUID foreignPatientId = UUID.randomUUID();
        UUID unknownPatientId = UUID.randomUUID();
        when(patientRepository.findByIdUnscoped(unknownPatientId)).thenReturn(Optional.empty());

        ResourceNotFoundException refused = refusal(forPatientId(foreignPatientId), patientUser);
        ResourceNotFoundException unknownToStaff = refusal(forPatientId(unknownPatientId), receptionist);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage().replace(foreignPatientId.toString(), "<id>"))
            .isEqualTo(unknownToStaff.getMessage().replace(unknownPatientId.toString(), "<id>"));
        verify(patientRepository, never()).findByIdUnscoped(foreignPatientId);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("another patient's username or email answers as an unknown one")
    void foreignUsernameAndEmailAnswerAsUnknown() {
        AppointmentRequestDTO byUsername = new AppointmentRequestDTO();
        byUsername.setPatientUsername("someone.else");
        assertThat(refusal(byUsername, patientUser).getMessage())
            .isEqualTo(new ResourceNotFoundException("user.notFoundByUsername", "someone.else").getMessage());

        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        other.setUser(user("someone.else", "ROLE_PATIENT"));
        when(patientRepository.findByEmailContainingIgnoreCase("x@y.test")).thenReturn(List.of(other));
        when(patientRepository.findByEmailContainingIgnoreCase("nobody@y.test")).thenReturn(List.of());
        AppointmentRequestDTO byEmail = new AppointmentRequestDTO();
        byEmail.setPatientEmail("x@y.test");
        AppointmentRequestDTO byUnknownEmail = new AppointmentRequestDTO();
        byUnknownEmail.setPatientEmail("nobody@y.test");
        assertThat(refusal(byEmail, patientUser).getMessage().replace("x@y.test", "<e>"))
            .isEqualTo(refusal(byUnknownEmail, patientUser).getMessage().replace("nobody@y.test", "<e>"));
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("their own id passes the patient step (the booking goes on to the hospital)")
    void ownPatientIdProceeds() {
        Patient own = new Patient();
        own.setId(UUID.randomUUID());
        own.setUser(patientUser);
        when(patientRepository.existsByIdAndUserId(own.getId(), patientUser.getId())).thenReturn(true);
        when(patientRepository.findByIdUnscoped(own.getId())).thenReturn(Optional.of(own));
        AppointmentRequestDTO request = forPatientId(own.getId());
        when(hospitalRepository.findById(request.getHospitalId())).thenReturn(Optional.empty());

        ResourceNotFoundException stoppedLater = refusal(request, patientUser);

        assertThat(stoppedLater.getMessage()).isEqualTo(new ResourceNotFoundException("hospital.notfound", request.getHospitalId()).getMessage());
        verify(patientRepository).findByIdUnscoped(own.getId());
    }
}
