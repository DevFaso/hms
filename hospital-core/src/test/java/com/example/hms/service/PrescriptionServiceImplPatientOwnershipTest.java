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

    /**
     * Real, not mocked: identity resolution IS what this guard gets wrong when
     * it gets it wrong. A mock here would let the service pass whichever
     * resolver it used, including one that refuses an OIDC principal.
     */
    @org.mockito.Spy
    private com.example.hms.controller.support.ControllerAuthUtils authUtils =
        new com.example.hms.controller.support.ControllerAuthUtils(null);

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
        when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A password-path principal: {@code CustomUserDetails} carrying the HMS user id. */
    private void authenticateAs(String... roles) {
        var authorities = List.of(roles).stream().map(SimpleGrantedAuthority::new).toList();
        var principal = new com.example.hms.security.CustomUserDetails(
            callerUserId, "caller", "pw", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, "n", authorities));
    }

    /**
     * An OIDC principal: a {@code JwtAuthenticationToken} whose HMS user id is
     * the {@code appUserId} claim, as {@code KeycloakJwtAuthenticationConverter}
     * produces. {@code AuthService.getCurrentUserId()} throws on this shape,
     * which is why the guard resolves through {@code ControllerAuthUtils}.
     */
    private void authenticateViaOidcAs(String... roles) {
        var authorities = List.of(roles).stream().map(SimpleGrantedAuthority::new).toList();
        org.springframework.security.oauth2.jwt.Jwt jwt =
            org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("sub", "keycloak-subject")
                .claim("appUserId", callerUserId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken(jwt, authorities));
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
            .thenReturn(withExchange(id));
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
        // The refusal must be "this is not yours", not "the guard never asked":
        // a guard that stopped consulting the repository and refused everyone
        // would satisfy the assertion above.
        org.mockito.Mockito.verify(patientRepository)
            .existsByIdAndUserId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(callerUserId));

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
        when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(false);
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
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST",
                "ROLE_PHARMACY_VERIFIER")) {
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

    @Test
    @DisplayName("an OIDC patient reads their own prescription (appUserId claim, not CustomUserDetails)")
    void oidcPatientReadsTheirOwn() {
        authenticateViaOidcAs("ROLE_PATIENT");
        UUID id = prescriptionFor(callerPatient);

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("an OIDC patient is still refused somebody else's")
    void oidcPatientRefusedAnothers() {
        authenticateViaOidcAs("ROLE_PATIENT");
        UUID id = prescriptionFor(otherPatient());

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("a pharmacy verifier who is also a patient gets the read-back of their own write")
    void pharmacyVerifierWhoIsAlsoAPatientGetsTheReadBack() {
        authenticateAs("ROLE_PATIENT", "ROLE_PHARMACY_VERIFIER");
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionAfterWrite(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("and reads a colleague's order too, because #737 admits the role on the read")
    void pharmacyVerifierWhoIsAlsoAPatientReadsAsAClinician() {
        // Not a patient-door exemption: since #737 the by-id annotation admits
        // ROLE_PHARMACY_VERIFIER outright, so the set mirrors it and the role
        // reads as the clinician it is. The verifier may RAISE a clarification,
        // so withholding the prescriber's answer would leave its own question
        // unanswerable.
        authenticateAs("ROLE_PATIENT", "ROLE_PHARMACY_VERIFIER");
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("the read-back still refuses a prescription at another hospital")
    void readBackKeepsHospitalScope() {
        authenticateAs("ROLE_PHARMACY_VERIFIER");
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        UUID id = UUID.randomUUID();
        com.example.hms.model.Prescription prescription = new com.example.hms.model.Prescription();
        prescription.setId(id);
        prescription.setHospital(elsewhere);
        prescription.setPatient(otherPatient());
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.getPrescriptionAfterWrite(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("a surgeon who is also a patient reads colleagues over a password login")
    void surgeonWhoIsAlsoAPatientIsUnaffectedOnThePasswordPath() {
        // RoleExpansion collapses SURGEON to DOCTOR on this path, so the
        // principal that actually reaches the handler is a doctor.
        authenticateAs(com.example.hms.security.RoleExpansion
            .expand(List.of("ROLE_SURGEON", "ROLE_PATIENT")).toArray(new String[0]));
        UUID id = prescriptionFor(otherPatient());

        assertThat(service.getPrescriptionById(id, Locale.ENGLISH).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("and is refused them over SSO, where the expansion never runs")
    void surgeonOverOidcIsRefusedBecauseTheExpansionDoesNotRunThere() {
        // Documented, not desired: KeycloakJwtAuthenticationConverter maps realm
        // roles straight through, so this principal reaches the handler only via
        // ROLE_PATIENT and the annotation never saw a clinician. A refusal is the
        // safe direction; widening the exemption to roles the annotation does not
        // admit is what round 2 removed. The fix belongs on the OIDC path.
        authenticateViaOidcAs("ROLE_SURGEON", "ROLE_PATIENT");
        UUID id = prescriptionFor(otherPatient());

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("an unexpanded super-admin is refused too, for the same reason")
    void unexpandedSuperAdminIsRefused() {
        // Same OIDC gap as the surgeon above, and the same answer: this read does
        // not admit ROLE_SUPER_ADMIN, so the principal is here on ROLE_PATIENT
        // alone. The super-admin's own surface is GET /prescriptions, which does
        // admit the role.
        authenticateViaOidcAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT");
        UUID id = prescriptionFor(otherPatient());

        assertThatThrownBy(() -> service.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("duplicate patient rows for one account answer correctly, not with a 500")
    void duplicatePatientRowsDoNotBlowUp() {
        // V113 falls back to a plain index rather than failing the deploy when a
        // tenant already carries duplicate clinical.patients.user_id rows, so the
        // single-result finder would throw there. The owner must still read their
        // own prescription, and still be refused a stranger's.
        // The membership query answers the same however many rows the account
        // owns, which is the point: no IncorrectResultSizeDataAccessException,
        // and no arbitrary pick that could refuse the owner.
        when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(true);
        authenticateAs("ROLE_PATIENT");

        UUID own = prescriptionFor(callerPatient);
        assertThat(service.getPrescriptionById(own, Locale.ENGLISH).getId()).isEqualTo(own);

        UUID strangers = prescriptionFor(otherPatient());
        assertThatThrownBy(() -> service.getPrescriptionById(strangers, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(NOT_FOUND_KEY);
    }

    // ==================================================================
    // Staff who are also patients, and the null-scope fail-open: the
    // prescription twin of #754 and #746.
    // ==================================================================

    /** A prescription at ANOTHER hospital than the caller's scope, written for {@code subject}. */
    private UUID prescriptionElsewhereFor(Patient subject) {
        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        return prescriptionAt(elsewhere, subject);
    }

    /** A prescription at {@code where}, which may be {@code null}, written for {@code subject}. */
    private UUID prescriptionAt(Hospital where, Patient subject) {
        UUID id = UUID.randomUUID();
        Prescription prescription = new Prescription();
        prescription.setId(id);
        prescription.setHospital(where);
        prescription.setPatient(subject);
        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription))
            .thenReturn(withExchange(id));
        return id;
    }

    private UUID missingPrescriptionId() {
        UUID missing = UUID.randomUUID();
        when(prescriptionRepository.findById(missing)).thenReturn(Optional.empty());
        return missing;
    }

    /** The refusal and the absence carry the same type, key and message. */
    private void assertRefusedLikeAMiss(UUID refusedId) {
        UUID missing = missingPrescriptionId();
        Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(
            () -> service.getPrescriptionById(refusedId, Locale.ENGLISH));
        Throwable absent = org.assertj.core.api.Assertions.catchThrowable(
            () -> service.getPrescriptionById(missing, Locale.ENGLISH));

        assertThat(refusal).isInstanceOf(ResourceNotFoundException.class);
        assertThat(absent).isInstanceOf(ResourceNotFoundException.class);
        assertThat(refusal.getMessage()).isEqualTo(absent.getMessage()).contains(NOT_FOUND_KEY);
    }

    @Test
    @DisplayName("a nurse who was a patient at ANOTHER hospital reads her own prescription from it")
    void staffWhoIsAlsoAPatientReadsTheirOwnPrescriptionAtAnotherHospital() {
        // ROLE_NURSE makes her a clinical reader, so she is held to her own
        // hospital, which refused her her OWN prescription from the hospital
        // that treated her, on an endpoint that admits ROLE_PATIENT.
        authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
        UUID mineElsewhere = prescriptionElsewhereFor(callerPatient);

        assertThat(service.getPrescriptionById(mineElsewhere, Locale.ENGLISH).getId()).isEqualTo(mineElsewhere);
    }

    @Test
    @DisplayName("and over SSO, where the user id is the appUserId claim of a Keycloak token")
    void oidcStaffWhoIsAlsoAPatientReadsTheirOwnPrescriptionAtAnotherHospital() {
        authenticateViaOidcAs("ROLE_NURSE", "ROLE_PATIENT");
        UUID mineElsewhere = prescriptionElsewhereFor(callerPatient);

        assertThat(service.getPrescriptionById(mineElsewhere, Locale.ENGLISH).getId()).isEqualTo(mineElsewhere);
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never()).getCurrentUserId();
    }

    @Test
    @DisplayName("that nurse is still refused a stranger's prescription at another hospital, like a miss")
    void staffWhoIsAlsoAPatientIsRefusedAStrangersPrescriptionElsewhere() {
        authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
        UUID strangersElsewhere = prescriptionElsewhereFor(otherPatient());

        assertRefusedLikeAMiss(strangersElsewhere);
    }

    @Test
    @DisplayName("a nurse linked to the patient row but WITHOUT ROLE_PATIENT is refused it elsewhere")
    void linkedStaffWithoutPatientRoleIsRefusedTheirRowElsewhere() {
        // The account is linked (existsByIdAndUserId is true, stubbed in
        // setUp) but the patient grant was never given or has been revoked:
        // the link is a fact about the account, not a grant.
        authenticateAs("ROLE_NURSE");
        UUID linkedElsewhere = prescriptionElsewhereFor(callerPatient);

        assertRefusedLikeAMiss(linkedElsewhere);
    }

    @Test
    @DisplayName("a patient scoped to one hospital reads their own prescription from another")
    void patientReadsTheirOwnPrescriptionAtAnotherHospital() {
        authenticateAs("ROLE_PATIENT");
        UUID mineElsewhere = prescriptionElsewhereFor(callerPatient);

        assertThat(service.getPrescriptionById(mineElsewhere, Locale.ENGLISH).getId()).isEqualTo(mineElsewhere);
    }

    @Test
    @DisplayName("an expanded super-admin pinned to one hospital is still bounded by it for a stranger's")
    void pinnedExpandedSuperAdminIsBoundedForAStrangersPrescription() {
        // RoleExpansion gives an expanded super-admin ROLE_PATIENT, so the
        // fallback is live for them; it opens only what their OWN account owns.
        authenticateAs(com.example.hms.security.RoleExpansion
            .expand(List.of("ROLE_SUPER_ADMIN")).toArray(new String[0]));
        UUID strangersElsewhere = prescriptionElsewhereFor(otherPatient());

        assertRefusedLikeAMiss(strangersElsewhere);
    }

    @Test
    @DisplayName("an authorities-only super-admin with no hospital is refused before the lookup")
    void authoritiesOnlySuperAdminWithNullScopeIsRefused() {
        // requireActiveHospitalId()'s step 4: the authorities say super-admin,
        // the verified flag does not, and the scope comes back null. That null
        // used to read every tenant's prescriptions.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
        authenticateAs("ROLE_SUPER_ADMIN");
        UUID strangersElsewhere = prescriptionElsewhereFor(otherPatient());
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> service.getPrescriptionById(strangersElsewhere, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("Hospital context required");
        assertThatThrownBy(() -> service.getPrescriptionById(missing, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("Hospital context required");
        // Refused before the row is read, so the answer cannot say whether the id is real.
        org.mockito.Mockito.verify(prescriptionRepository, org.mockito.Mockito.never())
            .findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a verified super-admin in global view still reads any hospital's prescription")
    void verifiedSuperAdminInGlobalViewStillReads() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        authenticateAs(com.example.hms.security.RoleExpansion
            .expand(List.of("ROLE_SUPER_ADMIN")).toArray(new String[0]));
        UUID strangersElsewhere = prescriptionElsewhereFor(otherPatient());

        assertThat(service.getPrescriptionById(strangersElsewhere, Locale.ENGLISH).getId())
            .isEqualTo(strangersElsewhere);
    }

    /** The clinical copy: a mapped DTO carrying hospital B's pharmacist-to-prescriber exchange. */
    private static PrescriptionResponseDTO withExchange(UUID id) {
        return PrescriptionResponseDTO.builder()
            .id(id)
            .clarificationReason("Dose exceeds the renal maximum?")
            .clarificationRequestedAt(java.time.LocalDateTime.of(2026, 9, 24, 8, 0))
            .clarificationResponse("Reduced to 250 mg.")
            .clarificationResolvedAt(java.time.LocalDateTime.of(2026, 9, 24, 9, 0))
            .build();
    }

    private static void assertPatientCopy(PrescriptionResponseDTO dto) {
        assertThat(dto.getClarificationReason()).isNull();
        assertThat(dto.getClarificationRequestedAt()).isNull();
        assertThat(dto.getClarificationResponse()).isNull();
        assertThat(dto.getClarificationResolvedAt()).isNull();
    }

    @Test
    @DisplayName("the nurse reading her own prescription elsewhere gets the PATIENT copy, without B's exchange")
    void staffOwnerFallbackReturnsThePatientCopy() {
        // She is at hospital B only as its patient. The controller strips the
        // exchange for patient-only callers, which she is not, so the service
        // must hand her the patient copy itself.
        authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
        UUID mineElsewhere = prescriptionElsewhereFor(callerPatient);

        assertPatientCopy(service.getPrescriptionById(mineElsewhere, Locale.ENGLISH));
    }

    @Test
    @DisplayName("and the same nurse at her own hospital, as staff, still gets the clinical copy")
    void staffAtTheirOwnHospitalKeepTheExchange() {
        authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
        UUID here = prescriptionFor(otherPatient());
        UUID mineHere = prescriptionFor(callerPatient);

        assertThat(service.getPrescriptionById(here, Locale.ENGLISH).getClarificationResponse())
            .isEqualTo("Reduced to 250 mg.");
        assertThat(service.getPrescriptionById(mineHere, Locale.ENGLISH).getClarificationReason())
            .isEqualTo("Dose exceeds the renal maximum?");
    }

    @Test
    @DisplayName("a patient with no resolvable hospital still reads their own, bounded by ownership")
    void patientOnlyWithNoHospitalReadsTheirOwn() {
        // No X-Hospital-Id, no context, not exactly one active assignment:
        // requireActiveHospitalId() has nothing to give. A patient-only caller
        // is bounded by ownership, as on the encounter reads, so the scope is
        // never asked for.
        when(roleValidator.requireActiveHospitalId())
            .thenThrow(new com.example.hms.exception.BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED));
        authenticateAs("ROLE_PATIENT");
        UUID mineElsewhere = prescriptionElsewhereFor(callerPatient);

        PrescriptionResponseDTO dto = service.getPrescriptionById(mineElsewhere, Locale.ENGLISH);
        assertThat(dto.getId()).isEqualTo(mineElsewhere);
        assertPatientCopy(dto);
        org.mockito.Mockito.verify(roleValidator, org.mockito.Mockito.never()).requireActiveHospitalId();
    }

    @Test
    @DisplayName("and is refused a stranger's exactly like a miss")
    void patientOnlyWithNoHospitalIsRefusedAStrangers() {
        when(roleValidator.requireActiveHospitalId())
            .thenThrow(new com.example.hms.exception.BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED));
        authenticateAs("ROLE_PATIENT");
        UUID strangersElsewhere = prescriptionElsewhereFor(otherPatient());

        assertRefusedLikeAMiss(strangersElsewhere);
    }

    @Test
    @DisplayName("a prescription with no hospital is not read as its patient, on either patient road")
    void unplacedPrescriptionIsReadAsItsPatientByNoOne() {
        // Prescription.hospital is nullable = false, so no stored row gets
        // here; the rule is that a row we cannot place is not handed out by
        // ownership alone, on the staff-owner fallback as on the patient road.
        authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
        assertRefusedLikeAMiss(prescriptionAt(null, callerPatient));

        SecurityContextHolder.clearContext();
        authenticateAs("ROLE_PATIENT");
        assertRefusedLikeAMiss(prescriptionAt(null, callerPatient));
    }
}
