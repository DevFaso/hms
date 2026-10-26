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
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

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

    @Test
    @DisplayName("QA D401: staff booking for a patient not registered at the hospital answers 404 and saves nothing")
    void staffBookingForUnregisteredPatientIsRefused() {
        User admin = user("platform.admin", "ROLE_SUPER_ADMIN");
        when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
        Patient onlyAtA = new Patient();
        onlyAtA.setId(UUID.randomUUID());
        when(patientRepository.findByIdUnscoped(onlyAtA.getId())).thenReturn(Optional.of(onlyAtA));
        AppointmentRequestDTO request = forPatientId(onlyAtA.getId());
        com.example.hms.model.Hospital hospitalB = new com.example.hms.model.Hospital();
        hospitalB.setId(request.getHospitalId());
        when(hospitalRepository.findClinicalById(hospitalB.getId())).thenReturn(Optional.of(hospitalB));
        doThrow(new ResourceNotFoundException("patient.notfound", onlyAtA.getId()))
            .when(patientRegistrationGuard).requireRegisteredAt(onlyAtA.getId(), hospitalB.getId());

        ResourceNotFoundException refused = refusal(request, admin);

        assertThat(refused).isNotNull();
        verify(patientRegistrationGuard).requireRegisteredAt(onlyAtA.getId(), hospitalB.getId());
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D401: a patient booking for themselves is not put through the staff registration check")
    void ownBookingSkipsStaffRegistrationCheck() {
        Patient own = new Patient();
        own.setId(UUID.randomUUID());
        own.setUser(patientUser);
        when(patientRepository.existsByIdAndUserId(own.getId(), patientUser.getId())).thenReturn(true);
        when(patientRepository.findByIdUnscoped(own.getId())).thenReturn(Optional.of(own));
        AppointmentRequestDTO request = forPatientId(own.getId());
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(request.getHospitalId());
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));

        catchThrowableOfType(RuntimeException.class,
            () -> service.createAppointment(request, locale, patientUser.getUsername()));

        verify(patientRegistrationGuard, never()).requireRegisteredAt(any(), any());
    }

    /**
     * QA D823 (re-test of D810): midwife A, assigned at hospital A, books the
     * prenatal visit "Planifier" opens for a patient registered at A (GET
     * /patients/{id} answered 200 for her). The booking answered 404 "patient
     * not found" because the service took every role outside admin, doctor,
     * nurse and receptionist for a patient booking for herself. Every staff
     * role the controller admits now books as staff: the patient is loaded by
     * id and put through the same registration check a receptionist's booking
     * is, and no "is this your own record" check runs.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "ROLE_MIDWIFE", "ROLE_STAFF", "ROLE_RECEPTIONIST", "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_HOSPITAL_ADMIN"})
    @DisplayName("QA D823: every staff booking role books for a registered patient as staff, not as that patient")
    void staffBookingRoleBooksForARegisteredPatient(String roleCode) {
        com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
        hospitalA.setId(UUID.randomUUID());
        User staffUser = new User();
        staffUser.setId(UUID.randomUUID());
        staffUser.setUsername("staff." + roleCode.toLowerCase(Locale.ROOT));
        staffUser.setUserRoles(Set.of());
        Role role = new Role();
        role.setCode(roleCode);
        com.example.hms.model.UserRoleHospitalAssignment assignment = new com.example.hms.model.UserRoleHospitalAssignment();
        assignment.setUser(staffUser);
        assignment.setRole(role);
        assignment.setHospital(hospitalA);
        assignment.setActive(true);
        when(userRepository.findByUsername(staffUser.getUsername())).thenReturn(Optional.of(staffUser));
        when(assignmentRepository.findAllByUserId(staffUser.getId())).thenReturn(List.of(assignment));

        Patient rasmata = new Patient();
        rasmata.setId(UUID.randomUUID());
        when(patientRepository.findByIdUnscoped(rasmata.getId())).thenReturn(Optional.of(rasmata));
        when(hospitalRepository.findClinicalById(hospitalA.getId())).thenReturn(Optional.of(hospitalA));
        AppointmentRequestDTO request = forPatientId(rasmata.getId());
        request.setHospitalId(hospitalA.getId());
        // The registration check passed means the patient step is behind us;
        // stop there rather than wire the staff, department and schedule.
        IllegalStateException pastThePatientStep = new IllegalStateException("past the patient step");
        doThrow(pastThePatientStep).when(patientRegistrationGuard).requireRegisteredAt(rasmata.getId(), hospitalA.getId());

        Throwable outcome = org.assertj.core.api.Assertions.catchThrowable(
            () -> service.createAppointment(request, locale, staffUser.getUsername()));

        assertThat(outcome).isSameAs(pastThePatientStep);
        verify(patientRepository, never()).existsByIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("QA D351: a staff booking keeps the reason it was booked for")
    void bookingKeepsTheReason() {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setReason("Fièvre et céphalées");
        request.setNotes("Apporter le carnet");
        com.example.hms.model.Appointment appointment = new com.example.hms.model.Appointment();

        AppointmentServiceImpl.applyBookingText(appointment, request);

        assertThat(appointment.getReason()).isEqualTo("Fièvre et céphalées");
        assertThat(appointment.getNotes()).isEqualTo("Apporter le carnet");
    }
}
