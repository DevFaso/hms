package com.example.hms.service;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D626 (verification): the account and the patient record are one write. */
class PatientAccountRegistrationServiceTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private UserService userService;
    private PatientService patientService;
    private PatientAccountRegistrationService service;
    private ActingScopeResolver resolver;
    private com.example.hms.repository.PatientRepository patientRepository;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        patientService = mock(PatientService.class);
        resolver = mock(ActingScopeResolver.class);
        when(resolver.current()).thenReturn(new ActingScope.Global(null));
        patientRepository = mock(com.example.hms.repository.PatientRepository.class);
        service = new PatientAccountRegistrationService(userService, patientService, validator, resolver,
            patientRepository);
    }

    private static AdminSignupRequest signup(UUID hospitalId, PatientRequestDTO patient) {
        AdminSignupRequest req = new AdminSignupRequest();
        req.setRoleNames(Set.of("PATIENT"));
        req.setHospitalId(hospitalId);
        req.setPatientRegistration(true);
        req.setPatient(patient);
        return req;
    }

    private static PatientRequestDTO validPatient() {
        PatientRequestDTO p = new PatientRequestDTO();
        p.setFirstName("QA");
        p.setLastName("Patient");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setGender("F");
        p.setPhoneNumberPrimary("+22670000000");
        return p;
    }

    @Test
    void createsTheRecordForTheNewAccountAtTheRequestedHospital() {
        UUID hospital = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UserResponseDTO user = new UserResponseDTO();
        user.setId(userId);
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(user);
        PatientResponseDTO created = new PatientResponseDTO();
        created.setId(patientId);
        when(patientService.createPatientByStaff(any(), eq(Locale.FRENCH))).thenReturn(created);

        UserResponseDTO result = service.registerPatient(signup(hospital, validPatient()), Locale.FRENCH);

        ArgumentCaptor<PatientRequestDTO> sent = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(patientService).createPatientByStaff(sent.capture(), eq(Locale.FRENCH));
        assertThat(sent.getValue().getUserId()).isEqualTo(userId);
        assertThat(sent.getValue().getHospitalId()).isEqualTo(hospital);
        assertThat(result.getPatientId()).isEqualTo(patientId);
    }

    @Test
    void thePinnedActingHospitalWinsOverTheBody() {
        UUID pinned = UUID.randomUUID();
        when(resolver.current()).thenReturn(new ActingScope.Pinned(pinned, ActingScope.Source.REQUESTED));
        UserResponseDTO user = new UserResponseDTO();
        user.setId(UUID.randomUUID());
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(user);
        when(patientService.createPatientByStaff(any(), any())).thenReturn(new PatientResponseDTO());

        service.registerPatient(signup(UUID.randomUUID(), validPatient()), Locale.ENGLISH);

        ArgumentCaptor<PatientRequestDTO> sent = ArgumentCaptor.forClass(PatientRequestDTO.class);
        verify(patientService).createPatientByStaff(sent.capture(), any());
        assertThat(sent.getValue().getHospitalId()).isEqualTo(pinned);
    }

    @Test
    void anInvalidRecordFailsTheWholeRegistration() {
        UserResponseDTO user = new UserResponseDTO();
        user.setId(UUID.randomUUID());
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(user);
        PatientRequestDTO noBirthDate = validPatient();
        noBirthDate.setDateOfBirth(null);

        AdminSignupRequest req = signup(UUID.randomUUID(), noBirthDate);
        assertThatThrownBy(() -> service.registerPatient(req, Locale.ENGLISH))
            .isInstanceOf(ConstraintViolationException.class);
        verify(patientService, never()).createPatientByStaff(any(), any());
    }

    @Test
    void registerPatientIsTransactional() throws NoSuchMethodException {
        assertThat(PatientAccountRegistrationService.class
            .getMethod("registerPatient", AdminSignupRequest.class, Locale.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    void qaD725AnIdentityThatAlreadyHasAPatientRecordIsRefusedNotSilentlyLinked() {
        // Round 10: hospital B registered a phone already used by a patient of
        // hospital A; the existing record was linked and B's typed fields lost.
        UUID hospitalB = UUID.randomUUID();
        UUID existingUserId = UUID.randomUUID();
        UserResponseDTO reused = new UserResponseDTO();
        reused.setId(existingUserId);
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(reused);
        when(patientRepository.findByUserId(existingUserId))
            .thenReturn(java.util.Optional.of(new com.example.hms.model.Patient()));

        AdminSignupRequest request = signup(hospitalB, validPatient());
        assertThatThrownBy(() -> service.registerPatient(request, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.PatientAlreadyRegisteredException.class);
        verify(patientService, never()).createPatientByStaff(any(), any());
    }

    @Test
    void qaD725AnExistingAccountWithoutAPatientRecordStillGetsOne() {
        // A staff member registered as a patient: the account exists, the record does not.
        UUID hospital = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserResponseDTO user = new UserResponseDTO();
        user.setId(userId);
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(user);
        when(patientRepository.findByUserId(userId)).thenReturn(java.util.Optional.empty());
        PatientResponseDTO created = new PatientResponseDTO();
        created.setId(UUID.randomUUID());
        when(patientService.createPatientByStaff(any(), any())).thenReturn(created);

        assertThat(service.registerPatient(signup(hospital, validPatient()), Locale.FRENCH).getPatientId())
            .isEqualTo(created.getId());
    }
}
