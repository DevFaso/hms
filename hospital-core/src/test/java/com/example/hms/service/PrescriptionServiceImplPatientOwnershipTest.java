package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@code GET /prescriptions/{id}} admits {@code ROLE_PATIENT}, and the
 * service enforced hospital scope and nothing else: within their own
 * hospital a patient holding any prescription id read somebody else's
 * medication, dose, frequency, duration and instructions. The clarification
 * exchange the controller strips is the pharmacist's notes — it was never a
 * check on whose prescription this is.
 *
 * <p>The answer to "not yours" must be indistinguishable from "does not
 * exist": {@link ResourceNotFoundException} carrying the same
 * {@code prescription.notfound} key, never a 403.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PrescriptionServiceImpl: a patient reads only their own prescription")
class PrescriptionServiceImplPatientOwnershipTest {

    private static final String NOT_FOUND_KEY = "prescription.notfound";

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private com.example.hms.repository.PatientAllergyRepository patientAllergyRepository;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private com.example.hms.repository.EncounterRepository encounterRepository;
    @Mock private PrescriptionMapper prescriptionMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuthService authService;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository urhaRepository;
    @Mock private com.example.hms.cdshooks.rules.CdsRuleEngine cdsRuleEngine;
    @Mock private com.example.hms.service.pharmacy.ControlledSubstanceGuard controlledSubstanceGuard;
    @Mock private com.example.hms.service.pharmacy.PharmacistVerificationService pharmacistVerificationService;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    @org.mockito.Spy
    private java.time.Clock clock = java.time.Clock.fixed(
        java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneOffset.UTC);

    @InjectMocks
    private PrescriptionServiceImpl service;

    private UUID hospitalId;
    private UUID callerUserId;
    private UUID callerPatientId;
    private Patient callerPatient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        callerPatientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        callerPatient = new Patient();
        callerPatient.setId(callerPatientId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(callerUserId);
        when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.of(callerPatient));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("caller", "n",
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /** A prescription at the caller's hospital, written for {@code subject}. */
    private UUID prescriptionFor(Patient subject) {
        UUID id = UUID.randomUUID();
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setHospital(hospital);
        prescription.setPatient(subject);
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription))
            .thenReturn(PrescriptionResponseDTO.builder().id(id).build());
        return id;
    }

    private Patient otherPatient() {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        return other;
    }

    @Test
    @DisplayName("another patient's prescription at the same hospital answers 404, as a missing id does")
    void anotherPatientsPrescriptionIsNotFound() {
        authenticateAs("ROLE_PATIENT");
        UUID id = prescriptionFor(otherPatient());

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);

        UUID missing = UUID.randomUUID();
        when(prescriptionRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPrescriptionById(missing, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("a patient whose account has no patient record reads nothing")
    void unlinkedPatientAccountIsNotFound() {
        authenticateAs("ROLE_PATIENT");
        when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());
        UUID id = prescriptionFor(callerPatient);

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a prescription with no patient on it is not anybody's")
    void prescriptionWithoutASubjectIsNotFound() {
        authenticateAs("ROLE_PATIENT");
        UUID id = prescriptionFor(null);

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a patient still reads their own prescription")
    void ownPrescriptionStillReads() {
        authenticateAs("ROLE_PATIENT");
        UUID id = prescriptionFor(callerPatient);

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("every clinical reader still reads any prescription at their hospital")
    void clinicalRolesAreUnaffected() {
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            UUID id = prescriptionFor(otherPatient());
            assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId())
                .as("role %s", role)
                .isEqualTo(id);
        }
    }

    @Test
    @DisplayName("a clinician who is also a patient is still a clinician")
    void clinicianWhoIsAlsoAPatientIsUnaffected() {
        authenticateAs("ROLE_PATIENT", "ROLE_DOCTOR");
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("a super-admin inherits ROLE_DOCTOR and is unaffected")
    void superAdminIsUnaffected() {
        authenticateAs(com.example.hms.security.RoleExpansion
            .expand(List.of("ROLE_SUPER_ADMIN")).toArray(new String[0]));
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("the clarification paths, which run unauthenticated in tests, are unaffected")
    void noAuthenticationIsNotAPatient() {
        SecurityContextHolder.clearContext();
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }
}
