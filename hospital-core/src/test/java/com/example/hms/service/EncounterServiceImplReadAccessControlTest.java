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
import com.example.hms.security.RoleExpansion;
import com.example.hms.security.oidc.KeycloakJwtAuthenticationConverter;
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
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
@DisplayName("EncounterServiceImpl: who may read an encounter, its AVS and its note history")
class EncounterServiceImplReadAccessControlTest {

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

        // No MessageSource stub: encounterNotFound() carries the KEY and the
        // id, and ResourceNotFoundException renders it through the static
        // MessageUtil — the injected MessageSource is never consulted on
        // these three paths. Wiring MessageUtil's static source from a test
        // would leak into every other test in the JVM, so these assertions
        // compare the two answers to each other rather than to a fixed
        // sentence.
        //
        // lenient() only on the shared fixtures a given case may not reach;
        // the suite is otherwise strict, so a dead stub inside a test fails it.
        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        // The ownership question, asked the way the guard asks it: is THIS
        // patient row linked to THIS user? Every other (row, user) pair is
        // false by Mockito's default, which is what "not yours" looks like.
        lenient().when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(true);
        lenient().when(encounterMapper.toEncounterResponseDTO(any(Encounter.class)))
            .thenReturn(new EncounterResponseDTO());
        lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(), any()))
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
     * An OIDC principal, built by the REAL {@code KeycloakJwtAuthenticationConverter}
     * from a token carrying the roles as realm roles, so it has exactly the
     * authorities production gives it (normalised and widened by
     * {@code RoleExpansion}). The HMS user id is the {@code appUserId} claim.
     * There is no {@code CustomUserDetails} on this shape, so
     * {@code RoleValidator.getCurrentUserId()} returns null for it, which is
     * why the guard resolves through {@code ControllerAuthUtils}.
     */
    private void authenticateViaOidcAs(String... roles) {
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "keycloak-subject")
            .claim("appUserId", callerUserId.toString())
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new KeycloakJwtAuthenticationConverter().convert(jwt));
    }

    /**
     * A password-path principal with the authorities
     * {@code JwtTokenProvider.getAuthenticationFromJwt} gives the same role
     * list: widened by {@code RoleExpansion}.
     */
    private void authenticateViaPasswordPathAs(String... roles) {
        authenticateAs(RoleExpansion.expand(List.of(roles)).toArray(new String[0]));
    }

    /** "read" when the call returns, otherwise the refusal's type and message. */
    private String outcomeOf(UUID encounterId) {
        try {
            service.getEncounterById(encounterId, locale);
            return "read";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private Encounter encounterAt(Hospital where, Patient subject, boolean checkedOut) {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(where);
        encounter.setPatient(subject);
        if (checkedOut) {
            encounter.setCheckoutTimestamp(LocalDateTime.of(2026, 9, 25, 10, 0));
        }
        lenient().when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
        return encounter;
    }

    /** An id no encounter carries, for the side-by-side indistinguishability assertion. */
    private UUID missingEncounterId() {
        UUID id = UUID.randomUUID();
        lenient().when(encounterRepository.findById(id)).thenReturn(Optional.empty());
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
    // The after-visit summary read: the one that had no boundary at all
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
        @DisplayName("ownership never goes through the single-result finder that 500s on duplicate rows")
        void ownershipDoesNotUseTheSingleResultFinder() {
            // findByUserId throws IncorrectResultSizeDataAccessException on a
            // tenant V113 left with duplicate user_id rows, and loads (and
            // decrypts) a whole Patient to compare two UUIDs.
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, true);

            assertThat(service.getAfterVisitSummary(mine.getId())).isNotNull();
            verify(patientRepository, never()).findByUserId(any());
        }

        @Test
        @DisplayName("an account linked to two patient rows reads the encounters on the second one too")
        void accountLinkedToTwoRowsReadsBoth() {
            Patient secondRow = new Patient();
            secondRow.setId(UUID.randomUUID());
            lenient().when(patientRepository.existsByIdAndUserId(secondRow.getId(), callerUserId)).thenReturn(true);
            authenticateAs("ROLE_PATIENT");
            Encounter onTheSecondRow = encounterAt(hospital, secondRow, true);

            assertThat(service.getAfterVisitSummary(onTheSecondRow.getId())).isNotNull();
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
            lenient().when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(false);
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, true);

            UUID mineId = mine.getId();

            assertThatThrownBy(() -> service.getAfterVisitSummary(mineId))
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
        @DisplayName("a clinician at two hospitals with no chosen scope keeps the actionable refusal")
        void noResolvableHospitalKeepsItsActionableMessage() {
            // requireActiveHospitalId() throws for a non-super-admin whose
            // scope cannot be resolved — a clinician with two active
            // assignments and no X-Hospital-Id. The scope is resolved BEFORE
            // any lookup, so telling them plainly to pick a hospital reveals
            // nothing about the id: a real id and a fictional one answer the
            // same way.
            lenient().when(roleValidator.requireActiveHospitalId())
                .thenThrow(new BusinessException("Hospital context required."));
            authenticateAs("ROLE_DOCTOR");
            Encounter here = encounterAt(hospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            UUID hereId = here.getId();

            assertThatThrownBy(() -> service.getAfterVisitSummary(hereId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
            assertThatThrownBy(() -> service.getAfterVisitSummary(missing))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
        }

        @Test
        @DisplayName("a super-admin who has pinned one hospital is bounded by it")
        void superAdminPinnedToOneHospitalIsBounded() {
            // requireActiveHospitalId() honours an X-Hospital-Id override for
            // a super-admin (the scope chip, #566). The guard no longer takes
            // its own super-admin decision from the authorities collection, so
            // a pinned super-admin is scoped like every other caller.
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            authenticateAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal = captureNotFound(() -> service.getAfterVisitSummary(elsewhere.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, elsewhere.getId(), absent, missing);
        }

        @ParameterizedTest(name = "{0} at another hospital is refused")
        @ValueSource(strings = {
            "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RECEPTIONIST"})
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
            "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RECEPTIONIST"})
        @DisplayName("every non-subject role in the AVS set still reads any AVS at its own hospital")
        void everyNonSubjectRoleStillReads(String role) {
            authenticateAs(role);
            Encounter strangers = encounterAt(hospital, strangerPatient, true);

            assertThat(service.getAfterVisitSummary(strangers.getId())).isNotNull();
        }

        @Test
        @DisplayName("a nurse who was a patient at ANOTHER hospital still reads her own AVS from it")
        void staffWhoIsAlsoAPatientReadsTheirOwnSummaryAtAnotherHospital() {
            // ROLE_NURSE makes her a non-subject reader, so she is held to her
            // own hospital — which refused her her OWN visit summary from the
            // hospital that treated her, on an endpoint that promises patients
            // may read their own. Ownership now lets her through.
            authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
            Encounter mineElsewhere = encounterAt(otherHospital, callerPatient, true);

            assertThat(service.getAfterVisitSummary(mineElsewhere.getId())).isNotNull();
        }

        @Test
        @DisplayName("a nurse linked to the patient row but WITHOUT ROLE_PATIENT is refused it elsewhere")
        void linkedStaffWithoutPatientRoleIsRefusedTheSummaryElsewhere() {
            // The account is linked to the patient row (existsByIdAndUserId is
            // true, stubbed in setUp), but the patient grant was never given or
            // has been revoked. Ownership must not open another hospital's
            // record through ROLE_NURSE alone — and the refusal must look like
            // a missing id.
            authenticateAs("ROLE_NURSE");
            Encounter linkedElsewhere = encounterAt(otherHospital, callerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getAfterVisitSummary(linkedElsewhere.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, linkedElsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("that nurse is still refused a stranger's AVS at another hospital, indistinguishably")
        void staffWhoIsAlsoAPatientIsStillRefusedAStrangersSummaryElsewhere() {
            authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
            Encounter strangersElsewhere = encounterAt(otherHospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getAfterVisitSummary(strangersElsewhere.getId()));
            ResourceNotFoundException absent = captureNotFound(() -> service.getAfterVisitSummary(missing));

            assertIndistinguishable(refusal, strangersElsewhere.getId(), absent, missing);
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
        @DisplayName("a verified super-admin reads across tenants, as the global view intends")
        void superAdminReadsCrossTenant() {
            // RoleExpansion.SUPER_ADMIN_INHERITS also grants ROLE_PATIENT on
            // the password path: the super-admin role must win over it.
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
            authenticateAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, true);

            assertThat(service.getAfterVisitSummary(elsewhere.getId())).isNotNull();
        }

        @Test
        @DisplayName("an unscoped read the verified flag does not back is refused, not opened")
        void unverifiedSuperAdminIsRefused() {
            // requireActiveHospitalId()'s step 4: null because the AUTHORITIES
            // say super-admin, while HospitalContext says not. The earlier
            // draft treated every null as "read across tenants", so this
            // principal read every hospital's after-visit summaries.
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, true);
            UUID missing = missingEncounterId();

            // Refused before the lookup, so a real id and a fictional one get
            // the same answer.
            UUID elsewhereId = elsewhere.getId();
            assertThatThrownBy(() -> service.getAfterVisitSummary(elsewhereId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
            assertThatThrownBy(() -> service.getAfterVisitSummary(missing))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
        }

        @Test
        @DisplayName("the refusal comes before 'not checked out yet', which would itself confirm the id")
        void scopeIsCheckedBeforeTheCheckoutState() {
            authenticateAs("ROLE_DOCTOR");
            Encounter elsewhereNotCheckedOut = encounterAt(otherHospital, strangerPatient, false);

            UUID elsewhereNotCheckedOutId = elsewhereNotCheckedOut.getId();

            assertThatThrownBy(() -> service.getAfterVisitSummary(elsewhereNotCheckedOutId))
                .isInstanceOf(ResourceNotFoundException.class)
                .isNotInstanceOf(BusinessException.class);
        }
    }

    // ==================================================================
    // The encounter detail read
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
            lenient().when(patientRepository.existsByIdAndUserId(callerPatientId, callerUserId)).thenReturn(false);
            authenticateAs("ROLE_PATIENT");
            Encounter mine = encounterAt(hospital, callerPatient, false);

            UUID mineId = mine.getId();

            assertThatThrownBy(() -> service.getEncounterById(mineId, locale))
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
            "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE",
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
            "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE",
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

            UUID strangersId = strangers.getId();

            assertThatThrownBy(() -> service.getEncounterById(strangersId, locale))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a nurse who was a patient at ANOTHER hospital still reads her own encounter from it")
        void staffWhoIsAlsoAPatientReadsTheirOwnEncounterAtAnotherHospital() {
            authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
            Encounter mineElsewhere = encounterAt(otherHospital, callerPatient, false);

            assertThat(service.getEncounterById(mineElsewhere.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a nurse linked to the patient row but WITHOUT ROLE_PATIENT is refused the encounter elsewhere")
        void linkedStaffWithoutPatientRoleIsRefusedTheEncounterElsewhere() {
            authenticateAs("ROLE_NURSE");
            Encounter linkedElsewhere = encounterAt(otherHospital, callerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getEncounterById(linkedElsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterById(missing, locale));

            assertIndistinguishable(refusal, linkedElsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("that nurse is still refused a stranger's encounter at another hospital, indistinguishably")
        void staffWhoIsAlsoAPatientIsStillRefusedAStrangersEncounterElsewhere() {
            authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
            Encounter strangersElsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getEncounterById(strangersElsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterById(missing, locale));

            assertIndistinguishable(refusal, strangersElsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("a clinician who is also a patient reads as a clinician")
        void clinicianWhoIsAlsoAPatientReadsAsAClinician() {
            authenticateAs("ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThat(service.getEncounterById(strangers.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a surgeon who is also a patient, over OIDC, reads as a clinician - RoleExpansion made them a doctor")
        void surgeonWhoIsAlsoAPatientOverOidcReadsAsAClinician() {
            // The detail annotation admits ROLE_DOCTOR, not ROLE_SURGEON. The
            // Keycloak converter now runs RoleExpansion, so this principal
            // holds ROLE_DOCTOR and reaches the handler as a clinician, exactly
            // as on the password path. ROLE_SURGEON is still absent from the
            // non-subject set: naming it there would be the escalation
            // EncounterReaderRoles describes, and it is not needed.
            authenticateViaOidcAs("ROLE_PATIENT", "ROLE_SURGEON");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThat(service.getEncounterById(strangers.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a surgeon who is also a patient gets the same answer over OIDC and over a password login")
        void surgeonWhoIsAlsoAPatientGetsTheSameAnswerOnBothPaths() {
            Encounter strangersHere = encounterAt(hospital, strangerPatient, false);
            Encounter strangersElsewhere = encounterAt(otherHospital, strangerPatient, false);
            Encounter mine = encounterAt(hospital, callerPatient, false);

            for (Encounter encounter : List.of(strangersHere, strangersElsewhere, mine)) {
                authenticateViaOidcAs("ROLE_SURGEON", "ROLE_PATIENT");
                String overOidc = outcomeOf(encounter.getId());
                authenticateViaPasswordPathAs("ROLE_SURGEON", "ROLE_PATIENT");
                String overPassword = outcomeOf(encounter.getId());

                assertThat(overOidc).isEqualTo(overPassword);
            }
            // And the answers are the clinician's: read here, refused elsewhere.
            assertThat(outcomeOf(strangersHere.getId())).isEqualTo("read");
            assertThat(outcomeOf(strangersElsewhere.getId())).isNotEqualTo("read");
        }

        @Test
        @DisplayName("that same surgeon-patient still reads their own encounter")
        void surgeonWhoIsAlsoAPatientStillReadsTheirOwn() {
            authenticateViaOidcAs("ROLE_PATIENT", "ROLE_SURGEON");
            Encounter mine = encounterAt(hospital, callerPatient, false);

            assertThat(service.getEncounterById(mine.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a surgeon who is also a patient reads as a clinician on the password path")
        void surgeonWhoIsAlsoAPatientOnThePasswordPathReadsAsAClinician() {
            // RoleExpansion has already given them ROLE_DOCTOR by the time any
            // guard runs, so dropping ROLE_SURGEON from the set costs a real
            // surgeon nothing on the login the annotation actually admits.
            authenticateViaPasswordPathAs("ROLE_PATIENT", "ROLE_SURGEON");
            Encounter strangers = encounterAt(hospital, strangerPatient, false);

            assertThat(service.getEncounterById(strangers.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("a verified super-admin reads across tenants, as the global view intends")
        void superAdminReadsCrossTenant() {
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
            authenticateAs("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);

            assertThat(service.getEncounterById(elsewhere.getId(), locale)).isNotNull();
        }

        @Test
        @DisplayName("an unscoped read the verified flag does not back is refused, not opened")
        void unverifiedSuperAdminIsRefused() {
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            UUID elsewhereId = elsewhere.getId();

            assertThatThrownBy(() -> service.getEncounterById(elsewhereId, locale))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
            assertThatThrownBy(() -> service.getEncounterById(missing, locale))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
        }
    }

    // ==================================================================
    // The note history read
    // ==================================================================

    @Nested
    @DisplayName("GET /encounters/{encounterId}/notes/history")
    class NoteHistory {

        @ParameterizedTest(name = "{0} at another hospital is refused")
        @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE"})
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
        @DisplayName("owning the encounter does not open another hospital's note trail — this endpoint admits no patient")
        void ownershipDoesNotWidenNoteHistory() {
            // The staff-who-are-also-patients fallback applies only where the
            // annotation admits ROLE_PATIENT. Note history does not, so a nurse
            // who owns the encounter at another hospital is refused exactly as
            // a missing id is.
            authenticateAs("ROLE_NURSE", "ROLE_PATIENT");
            Encounter mineElsewhere = encounterAt(otherHospital, callerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getEncounterNoteHistory(mineElsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterNoteHistory(missing, locale));

            assertIndistinguishable(refusal, mineElsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("a super-admin who has pinned one hospital is bounded by it, indistinguishably")
        void superAdminPinnedToOneHospitalIsBounded() {
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            ResourceNotFoundException refusal =
                captureNotFound(() -> service.getEncounterNoteHistory(elsewhere.getId(), locale));
            ResourceNotFoundException absent = captureNotFound(() -> service.getEncounterNoteHistory(missing, locale));

            assertIndistinguishable(refusal, elsewhere.getId(), absent, missing);
        }

        @Test
        @DisplayName("a clinician at the encounter's hospital still reads the trail")
        void clinicianAtTheHospitalStillReads() {
            authenticateAs("ROLE_DOCTOR");
            Encounter here = encounterAt(hospital, strangerPatient, false);
            lenient().when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(here.getId()))
                .thenReturn(List.of());

            assertThat(service.getEncounterNoteHistory(here.getId(), locale)).isEmpty();
        }

        @Test
        @DisplayName("an unscoped read the verified flag does not back is refused, not opened")
        void unverifiedSuperAdminIsRefused() {
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            UUID missing = missingEncounterId();

            UUID elsewhereId = elsewhere.getId();

            assertThatThrownBy(() -> service.getEncounterNoteHistory(elsewhereId, locale))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
            assertThatThrownBy(() -> service.getEncounterNoteHistory(missing, locale))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
        }

        @Test
        @DisplayName("a verified super-admin reads the trail across tenants")
        void superAdminReadsCrossTenant() {
            lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
            authenticateAs("ROLE_SUPER_ADMIN");
            Encounter elsewhere = encounterAt(otherHospital, strangerPatient, false);
            lenient().when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(elsewhere.getId()))
                .thenReturn(List.of());

            assertThat(service.getEncounterNoteHistory(elsewhere.getId(), locale)).isEmpty();
        }
    }
}
