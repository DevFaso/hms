package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Access control on the three encounter READS.
 *
 * <p>Two defects, and the second was unbounded.
 *
 * <ul>
 *   <li>{@code GET /encounters/&#123;encounterId&#125;/avs} did a bare
 *       {@code findById} and checked only that the encounter had been checked
 *       out — <b>no hospital scope and no ownership</b>. Its annotation admits
 *       a super-admin, three clinical roles, a receptionist and a patient, so
 *       any of them holding an encounter id read that visit's diagnoses,
 *       medications and discharge instructions at <b>any hospital on the
 *       platform</b>.</li>
 *   <li>{@code GET /encounters/&#123;id&#125;} had the hospital scope and not
 *       the ownership check, so a patient read another patient's encounter at
 *       their own hospital.</li>
 *   <li>{@code GET /encounters/&#123;encounterId&#125;/notes/history} — found
 *       by sweeping the rest of the controller — had neither, on a
 *       clinician-only role set: the note audit trail was platform-wide.</li>
 * </ul>
 *
 * <p>Every refusal is {@link ResourceNotFoundException} carrying exactly what
 * a missing id carries, asserted side by side, so no caller can use the
 * answer to confirm that an id is real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EncounterServiceImpl: who may read an encounter, its AVS and its note history")
class EncounterServiceImplReadAccessControlTest {

    /** The real {@code encounter.notfound} bundle string, {0} and all. */
    private static final String NOT_FOUND_TEMPLATE = "Encounter with ID {0} was not found.";

    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private EncounterMapper encounterMapper;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.mapper.CheckOutMapper checkOutMapper;
    @Mock private com.example.hms.repository.EncounterNoteHistoryRepository encounterNoteHistoryRepository;

    /**
     * Real, not mocked: identity resolution IS what an ownership guard gets
     * wrong when it gets it wrong. A mock here would let the service pass with
     * whichever resolver it used, including one that refuses an OIDC
     * principal. The repository argument is unused on the
     * {@code resolveUserId} path.
     */
    @Spy
    private com.example.hms.controller.support.ControllerAuthUtils authUtils =
        new com.example.hms.controller.support.ControllerAuthUtils(null);

    @InjectMocks private EncounterServiceImpl service;

    private final Locale locale = Locale.ENGLISH;

    private UUID hospitalId;
    private UUID otherHospitalId;
    private UUID callerUserId;
    private UUID callerPatientId;
    private Hospital hospital;
    private Hospital otherHospital;
    private Patient callerPatient;
    private Patient strangerPatient;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        otherHospitalId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        callerPatientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        otherHospital = new Hospital();
        otherHospital.setId(otherHospitalId);

        callerPatient = new Patient();
        callerPatient.setId(callerPatientId);
        strangerPatient = new Patient();
        strangerPatient.setId(UUID.randomUUID());

        // Deliberately NOT wiring MessageUtil's static MessageSource: that is
        // process-global and would leak into every other test in the JVM. The
        // rendered sentence is whatever MessageUtil produces; what these tests
        // assert is that the refusal and the absence produce the SAME one,
        // plus the same key and the requested id as the only argument.
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenAnswer(call -> java.text.MessageFormat.format(
                NOT_FOUND_TEMPLATE, (Object[]) call.getArgument(1)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.of(callerPatient));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class)))
            .thenReturn(new EncounterResponseDTO());
        when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(), any()))
            .thenReturn(new AfterVisitSummaryDTO());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A password-path principal: {@code CustomUserDetails} carrying the HMS user id. */
    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
            .map(SimpleGrantedAuthority::new).toList();
        com.example.hms.security.CustomUserDetails principal =
            new com.example.hms.security.CustomUserDetails(
                callerUserId, "caller", "pw", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, "n", authorities));
    }

    /**
     * An OIDC principal: a {@code JwtAuthenticationToken} whose HMS user id is
     * the {@code appUserId} claim, as
     * {@code KeycloakJwtAuthenticationConverter} produces. There is no
     * {@code CustomUserDetails} on this shape, so
     * {@code RoleValidator.getCurrentUserId()} returns null for it — which is
     * why the guard resolves through {@code ControllerAuthUtils}.
     */
    private void authenticateViaOidcAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
            .map(SimpleGrantedAuthority::new).toList();
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "keycloak-subject")
            .claim("appUserId", callerUserId.toString())
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, authorities));
    }

    private Encounter encounterAt(Hospital where, Patient subject, boolean checkedOut) {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(where);
        encounter.setPatient(subject);
        if (checkedOut) {
            encounter.setCheckoutTimestamp(LocalDateTime.of(2026, 9, 25, 10, 0));
        }
        when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
        return encounter;
    }

    /** An id no encounter carries, for the side-by-side indistinguishability assertion. */
    private UUID missingEncounterId() {
        UUID id = UUID.randomUUID();
        when(encounterRepository.findById(id)).thenReturn(Optional.empty());
        return id;
    }

    /**
     * Runs a read that is expected to be refused and hands back the
     * {@link ResourceNotFoundException} it threw, or {@code null} if it threw
     * nothing — so the refusal and the genuine not-found can be compared
     * field by field rather than merely both being 404s.
     */
    private ResourceNotFoundException captureNotFound(Runnable read) {
        try {
            read.run();
            return null;
        } catch (ResourceNotFoundException expected) {
            return expected;
        }
    }

    /**
     * The refusal and the genuine not-found must be the same answer.
     *
     * <p>The bundle message is {@code encounter.notfound} = "Encounter with ID
     * {0} was not found.", so each answer names the id its own caller asked
     * about; substituting that id back out is what makes the two comparable.
     * Same exception type, same key, same one argument (the requested id) and
     * the same rendered sentence around it — nothing in the answer depends on
     * whether the row exists.
     */
    private void assertIndistinguishable(ResourceNotFoundException refusal, UUID refusedId,
                                         ResourceNotFoundException absent, UUID absentId) {
        assertThat(refusal).isNotNull();
        assertThat(absent).isNotNull();
        assertThat(refusal.getMessageKey()).isEqualTo(absent.getMessageKey());
        assertThat(refusal.getArgs()).containsExactly(refusedId);
        assertThat(absent.getArgs()).containsExactly(absentId);
        assertThat(refusal.getMessage().replace(refusedId.toString(), "ID"))
            .isEqualTo(absent.getMessage().replace(absentId.toString(), "ID"));
    }

    // ==================================================================
    // GET /encounters/{encounterId}/avs — the unbounded one
    // ==================================================================

    @Nested
    @DisplayName("GET /encounters/{encounterId}/avs")
    class AfterVisitSummary {

        @Test
        @DisplayName("a patient cannot read another patient's AVS, and cannot tell that from a missing id")
        void patientCannotReadAnotherPatientsSummary() {
            authenticateAs("ROLE_PATIENT");
            Encounter strangers = encounterAt(hospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getAfterVisitSummary(strangers.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, strangers.getId(), absent, missing);
        }

        @Test
        @DisplayName("a patient still reads their own AVS (password login)")
        void patientReadsOwnSummary() {
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, true);

            assertThat(service.getAfterVisitSummary(mine.getId())).isNotNull();
        }

        @Test
        @DisplayName("a patient still reads their own AVS over OIDC (appUserId claim)")
        void patientReadsOwnSummaryOverOidc() {
            authenticateViaOidcAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, true);

            assertThat(service.getAfterVisitSummary(mine.getId())).isNotNull();
        }

        @Test
        @DisplayName("a patient account with no linked patient row is refused, not 500'd")
        void patientWithoutPatientRowIsRefused() {
            when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, true);

            assertThatThrownBy(() -> service.getAfterVisitSummary(mine.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a patient seen at a SECOND hospital still reads their own AVS from it")
        void patientReadsOwnSummaryAtAnotherHospital() {
            // Their own records follow them across tenants — the model
            // readEncountersForPatient states outright — and their JWT pins
            // one primary hospital. Bounding the subject by that hospital
            // would refuse them the summary of a visit they actually made.
            authenticateAs("ROLE_PATIENT");
            Encounter mineElsewhere = encounterAt(otherHospital, callerPatient, true);

            assertThat(service.getAfterVisitSummary(mineElsewhere.getId())).isNotNull();
        }

        @Test
        @DisplayName("a patient is still refused a stranger's AVS at another hospital")
        void patientIsRefusedAStrangersSummaryAtAnotherHospital() {
            authenticateAs("ROLE_PATIENT");
            Encounter strangersElsewhere = encounterAt(otherHospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getAfterVisitSummary(strangersElsewhere.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, strangersElsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("a caller with no resolvable hospital is refused, not told 'hospital context required'")
        void noResolvableHospitalIsAnOrdinaryNotFound() {
            // requireActiveHospitalId() throws BusinessException for a
            // non-super-admin with no scope. The guard runs only after the row
            // is found, so letting it out would answer a real id differently
            // from a fictional one.
            when(roleValidator.requireActiveHospitalId())
                .thenThrow(new BusinessException("Hospital context required."));
            authenticateAs("ROLE_DOCTOR");
            Encounter here = encounterAt(hospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getAfterVisitSummary(here.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, here.getId(), absent, missing);
        }

        @ParameterizedTest(name = "{0} at another hospital is refused")
        @ValueSource(strings = {
            "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON",
            "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RECEPTIONIST"})
        @DisplayName("every non-subject role in the AVS set is refused an AVS at another hospital")
        void everyRoleIsRefusedCrossTenant(String role) {
            authenticateAs(role);
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getAfterVisitSummary(elsewhere.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, elsewhere.getId(), absent, missing);
        }

        @ParameterizedTest(name = "{0} at the encounter's hospital still reads it")
        @ValueSource(strings = {
            "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON",
            "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RECEPTIONIST"})
        @DisplayName("every non-subject role in the AVS set still reads any AVS at its own hospital")
        void everyNonSubjectRoleStillReads(String role) {
            authenticateAs(role);
            Encounter strangers = encounterAt(hospital, strangerPatient, true);

            assertThat(service.getAfterVisitSummary(strangers.getId())).isNotNull();
        }

        @Test
        @DisplayName("a receptionist who is also a patient reads the front desk's AVS, not only their own")
        void receptionistWhoIsAlsoAPatientIsAFrontDeskReader() {
            // The AVS annotation admits ROLE_RECEPTIONIST, so on THIS endpoint
            // the front-desk role is the one that answers; contrast the detail
            // read below, which does not admit it.
            authenticateAs("ROLE_PATIENT", "ROLE_RECEPTIONIST");
            Encounter strangers = encounterAt(hospital, strangerPatient, true);

            assertThat(service.getAfterVisitSummary(strangers.getId())).isNotNull();
        }

        @Test
        @DisplayName("a super-admin reads across tenants, as the global view intends")
        void superAdminReadsCrossTenant() {
            // RoleExpansion.SUPER_ADMIN_INHERITS also grants ROLE_PATIENT on
            // the password path: the super-admin role must win over it.
            when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            authenticateAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, true);

            assertThat(service.getAfterVisitSummary(elsewhere.getId())).isNotNull();
        }

        @Test
        @DisplayName("the refusal comes before 'not checked out yet', which would itself confirm the id")
        void scopeIsCheckedBeforeTheCheckoutState() {
            authenticateAs("ROLE_DOCTOR");
            Encounter elsewhereNotCheckedOut = encounterAt(otherHospital, strangerPatient, false);

            assertThatThrownBy(() -> service.getAfterVisitSummary(elsewhereNotCheckedOut.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .isNotInstanceOf(BusinessException.class);
        }
    }

    // ==================================================================
    // GET /encounters/{id}
    // ==================================================================

    @Nested
    @DisplayName("GET /encounters/{id}")
    class EncounterDetail {

        @Test
        @DisplayName("a patient cannot read another patient's encounter, and cannot tell that from a missing id")
        void patientCannotReadAnotherPatientsEncounter() {
            authenticateAs("ROLE_PATIENT");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getEncounterById(strangers.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterById(missing, locale));

            assertIndistinguishable(refusal, strangers.getId(), absent, missing);
        }

        @Test
        @DisplayName("a patient still reads their own encounter (password login)")
        void patientReadsOwnEncounter() {
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, false);

            assertThat(service.getEncounterById(mine.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a patient still reads their own encounter over OIDC (appUserId claim)")
        void patientReadsOwnEncounterOverOidc() {
            authenticateViaOidcAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, false);

            assertThat(service.getEncounterById(mine.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a patient account with no linked patient row is refused, not 500'd")
        void patientWithoutPatientRowIsRefused() {
            when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.empty());
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, false);

            assertThatThrownBy(() -> service.getEncounterById(mine.getId(), locale))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a patient seen at a SECOND hospital still reads their own encounter from it")
        void patientReadsOwnEncounterAtAnotherHospital() {
            authenticateAs("ROLE_PATIENT");
            Encounter mineElsewhere = encounterAt(otherHospital, callerPatient, false);

            assertThat(service.getEncounterById(mineElsewhere.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a patient is still refused a stranger's encounter at another hospital")
        void patientIsRefusedAStrangersEncounterAtAnotherHospital() {
            authenticateAs("ROLE_PATIENT");
            Encounter strangersElsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getEncounterById(strangersElsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterById(missing, locale));

            assertIndistinguishable(refusal, strangersElsewhere.getId(), absent, missing);
        }

        @ParameterizedTest(name = "{0} at another hospital is refused")
        @ValueSource(strings = {
            "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE",
            "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST"})
        @DisplayName("every non-subject role in the detail set is refused an encounter at another hospital")
        void everyRoleIsRefusedCrossTenant(String role) {
            authenticateAs(role);
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getEncounterById(elsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterById(missing, locale));

            assertIndistinguishable(refusal, elsewhere.getId(), absent, missing);
        }

        @ParameterizedTest(name = "{0} at the encounter's hospital still reads it")
        @ValueSource(strings = {
            "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE",
            "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST"})
        @DisplayName("every non-subject role in the detail set still reads any encounter at its own hospital")
        void everyNonSubjectRoleStillReads(String role) {
            authenticateAs(role);
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThat(service.getEncounterById(strangers.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a receptionist who is also a patient gets in through the patient door, so only their own")
        void receptionistWhoIsAlsoAPatientIsStillJustAPatientHere() {
            // The detail annotation does NOT admit ROLE_RECEPTIONIST, so this
            // principal reaches the handler as a patient. Classing them as a
            // non-subject reader here — which a single union role set would —
            // would hand them every encounter at the hospital.
            authenticateAs("ROLE_PATIENT", "ROLE_RECEPTIONIST");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThatThrownBy(() -> service.getEncounterById(strangers.getId(), locale))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a clinician who is also a patient reads as a clinician")
        void clinicianWhoIsAlsoAPatientReadsAsAClinician() {
            authenticateAs("ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThat(service.getEncounterById(strangers.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a super-admin reads across tenants, as the global view intends")
        void superAdminReadsCrossTenant() {
            when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            authenticateAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);

            assertThat(service.getEncounterById(elsewhere.getId(), locale)).isNotNull();
        }
    }

    // ==================================================================
    // GET /encounters/{encounterId}/notes/history
    // ==================================================================

    @Nested
    @DisplayName("GET /encounters/{encounterId}/notes/history")
    class NoteHistory {

        @ParameterizedTest(name = "{0} at another hospital is refused")
        @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE"})
        @DisplayName("every role in the note-history set is refused a trail at another hospital")
        void everyRoleIsRefusedCrossTenant(String role) {
            authenticateAs(role);
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getEncounterNoteHistory(elsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterNoteHistory(missing, locale));

            assertIndistinguishable(refusal, elsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("a clinician at the encounter's hospital still reads the trail")
        void clinicianAtTheHospitalStillReads() {
            authenticateAs("ROLE_DOCTOR");
            Encounter here = encounterAt(hospital, strangerPatient, false);
            when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(here.getId()))
                .thenReturn(List.of());

            assertThat(service.getEncounterNoteHistory(here.getId(), locale)).isEmpty();
        }

        @Test
        @DisplayName("a super-admin reads the trail across tenants")
        void superAdminReadsCrossTenant() {
            when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(elsewhere.getId()))
                .thenReturn(List.of());

            assertThat(service.getEncounterNoteHistory(elsewhere.getId(), locale)).isEmpty();
        }
    }
}
