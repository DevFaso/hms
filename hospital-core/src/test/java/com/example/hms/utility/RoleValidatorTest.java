package com.example.hms.utility;

import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link RoleValidator}'s hospital-resolution
 * paths. The trigger for adding this test is a real production NPE on
 * {@code RoleValidator.getCurrentHospitalId} that surfaced when the
 * cross-tenant list-pages slice landed:
 *
 * <pre>
 * NullPointerException: Cannot invoke "Hospital.getId()" because the
 *   return value of "UserRoleHospitalAssignment.getHospital()" is null
 *     at RoleValidator.getCurrentHospitalId(RoleValidator.java:95)
 * </pre>
 *
 * The bug: when a super-admin user has exactly one active
 * {@code UserRoleHospitalAssignment} and that assignment is
 * <b>global</b> (no hospital attached), the code did
 * {@code .getHospital().getId()} on a null reference. This used to be
 * unreachable because super-admins always had {@code X-Hospital-Id}
 * set, but the cross-tenant "global view" deliberately omits the
 * header — so the fallback path now fires and used to crash 10
 * dashboard endpoints simultaneously.
 *
 * <p>Since the one tenant resolver ({@code ActingScopeResolver}) the
 * hospital-resolution methods here are adapters over it: the tests set the
 * context the auth filters produce instead of stubbing assignment queries.
 */
@ExtendWith(MockitoExtension.class)
class RoleValidatorTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    private RoleValidator roleValidator;

    @BeforeEach
    void setUp() {
        roleValidator = new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver(assignmentRepository, null));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    // ── getCurrentHospitalId ─────────────────────────────────────────

    @Test
    void getCurrentHospitalId_returnsNullWhenNoAuthenticatedUser() {
        // No SecurityContext → no userId → null
        assertThat(roleValidator.getCurrentHospitalId()).isNull();
    }

    /**
     * {@code getCurrentHospitalId()} used to run its own "exactly one active
     * assignment" query (and once NPE'd on a single GLOBAL assignment). It is
     * the one resolver's answer now: the pinned hospital, else null, with no
     * query of its own.
     */
    @Test
    void getCurrentHospitalId_isThePinnedHospitalOfTheResolver() {
        UUID hospitalId = UUID.randomUUID();
        ActingScopeTestSupport.actingAt(UUID.randomUUID(), hospitalId);
        assertThat(roleValidator.getCurrentHospitalId()).isEqualTo(hospitalId);

        // Several hospitals and none named: no hospital, not the "only" one.
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedHospitalIds(java.util.Set.of(UUID.randomUUID(), UUID.randomUUID()))
            .scopeRefusal(com.example.hms.security.tenant.ActingScope.Reason.AMBIGUOUS)
            .build());
        assertThat(roleValidator.getCurrentHospitalId()).isNull();

        // A super-admin in global view (a single global assignment): null, no NPE.
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        assertThat(roleValidator.getCurrentHospitalId()).isNull();

        Mockito.verifyNoInteractions(assignmentRepository);
    }

    // ── requireActiveHospitalId ──────────────────────────────────────

    @Test
    void requireActiveHospitalId_prefersHospitalContextWhenSet() {
        // Deliberately no SecurityContext / no authenticated principal:
        // when HospitalContext.activeHospitalId is set, the context
        // path must short-circuit BEFORE we ever look at the principal
        // or hit the repository. (No Mockito stubs needed — the
        // assignmentRepository should be untouched.)
        UUID contextHospital = UUID.randomUUID();
        HospitalContextHolder.setContext(
            HospitalContext.builder().activeHospitalId(contextHospital).build());

        assertThat(roleValidator.requireActiveHospitalId()).isEqualTo(contextHospital);
        Mockito.verifyNoInteractions(assignmentRepository);
    }

    /**
     * The global view a VERIFIED super-admin gets: null. And "step 4" is gone:
     * a principal whose AUTHORITIES say super-admin while no verified context
     * backs them (a test that skips the filter, an inflated authority list) is
     * refused like anyone with no hospital, never given an unscoped read.
     */
    @Test
    void requireActiveHospitalId_isNullOnlyForAVerifiedSuperAdminInGlobalView() {
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        assertThat(roleValidator.requireActiveHospitalId()).isNull();

        HospitalContextHolder.clear();
        setAuthenticatedUser(UUID.randomUUID(), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        org.assertj.core.api.Assertions.assertThatThrownBy(roleValidator::requireActiveHospitalId)
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessage(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        Mockito.verifyNoInteractions(assignmentRepository);
    }

    /**
     * Design Q1: a patient-only caller is bounded by ownership. Null means an
     * unscoped super-admin to every caller of this method, so a patient must
     * never receive it: the answer is a 403 PATIENT_OWNED, and the
     * patient-reached path takes its hospital from the record instead.
     */
    @Test
    void requireActiveHospitalId_refusesAPatientOwnedScopeInsteadOfAnsweringNull() {
        HospitalContextHolder.setContext(com.example.hms.security.context.HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(java.util.Set.of(UUID.randomUUID(), UUID.randomUUID()))
            .patientOwned(true)
            .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(roleValidator::requireActiveHospitalId)
            .isInstanceOf(com.example.hms.exception.HospitalScopeRefusedException.class)
            .extracting(e -> ((com.example.hms.exception.HospitalScopeRefusedException) e).getReason())
            .isEqualTo(com.example.hms.exception.HospitalScopeRefusedException.PATIENT_OWNED);
    }

    /**
     * Reproducer for the "click card → no data" cross-tenant bug
     * (commit f7e5a973's runtime symptom): JwtTokenProvider populates
     * {@code HospitalContext.activeHospitalId} from the
     * {@code primaryHospitalId} JWT claim — the super-admin's home
     * hospital — even when no {@code X-Hospital-Id} header is sent.
     * Before this fix, {@code requireActiveHospitalId()} returned that
     * primary hospital from step 1, silently re-scoping the unscoped
     * fallback path; the dashboard counters (which bypass RoleValidator
     * by calling {@code repository.count()} directly) showed the
     * correct global totals while the per-resource list pages returned
     * 0 rows because they were filtered to the super-admin's home
     * hospital. After the fix, super-admin (per JWT claim) short-circuits
     * to {@code null} when {@code headerOverridden} is false (no
     * explicit scope) regardless of any JWT-derived activeHospitalId.
     */
    @Test
    void requireActiveHospitalId_returnsNullForSuperAdmin_evenWhenJwtPopulatesActiveHospitalId() {
        // Real super-admin: JWT claim says so, AND JwtTokenProvider
        // pre-populated activeHospitalId from CLAIM_PRIMARY_HOSPITAL_ID.
        // headerOverridden=false because no X-Hospital-Id header was sent.
        HospitalContextHolder.setContext(
            HospitalContext.builder()
                .superAdmin(true)
                .activeHospitalId(UUID.randomUUID()) // primary hospital from JWT
                .headerOverridden(false)
                .build());

        assertThat(roleValidator.requireActiveHospitalId()).isNull();
        // Resolved entirely from HospitalContext — no DB lookup needed.
        Mockito.verifyNoInteractions(assignmentRepository);
    }

    /**
     * Companion to the test above (Copilot review on the F1 fixup,
     * 2026-05-06): when a super-admin <i>did</i> send an explicit
     * {@code X-Hospital-Id} header — chip-scoped view —
     * {@code HospitalContextRequestOverrides} flips
     * {@code headerOverridden=true}. The fallback must honour that
     * scope and return the header-derived hospital id, otherwise
     * scoped super-admin endpoints (LookupController, the
     * cross-tenant validators in EncounterTreatmentServiceImpl /
     * TreatmentPlanServiceImpl) silently run unscoped — which is
     * exactly the regression Copilot flagged.
     */
    @Test
    void requireActiveHospitalId_returnsScopedHospital_forSuperAdminWithExplicitHeaderOverride() {
        UUID scopedHospital = UUID.randomUUID();
        HospitalContextHolder.setContext(
            HospitalContext.builder()
                .superAdmin(true)
                .activeHospitalId(scopedHospital)
                .headerOverridden(true) // X-Hospital-Id header was sent
                .build());

        assertThat(roleValidator.requireActiveHospitalId()).isEqualTo(scopedHospital);
        Mockito.verifyNoInteractions(assignmentRepository);
    }

    /**
     * F1 impersonation correctness gap (design call #1). An impersonation
     * context (or any future code path that copies authorities verbatim)
     * could carry an inflated {@code ROLE_SUPER_ADMIN} authority while
     * the discrete {@code isSuperAdmin} JWT claim is {@code false}.
     * Before this fix, the authorities-based step would have either
     * returned {@code null} (cross-tenant data leak) or — depending on
     * order — returned the scoped hospital. After this fix, the JWT
     * claim is the source of truth: a non-real-super-admin with an
     * inflated authority must scope to {@code activeHospitalId}, not
     * fall through to the unscoped branch.
     */
    @Test
    void requireActiveHospitalId_returnsScopedHospital_whenAuthoritiesInflateSuperAdminButJwtClaimDoesNot() {
        // Authorities have ROLE_SUPER_ADMIN inflated…
        setAuthenticatedUser(UUID.randomUUID(),
            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        // …but the discrete JWT claim says NOT a real super-admin, AND
        // the request carries an explicit hospital scope (X-Hospital-Id).
        UUID scopedHospital = UUID.randomUUID();
        HospitalContextHolder.setContext(
            HospitalContext.builder()
                .superAdmin(false)               // ← the only safe signal
                .activeHospitalId(scopedHospital)
                .build());

        assertThat(roleValidator.requireActiveHospitalId()).isEqualTo(scopedHospital);
        // Resolved from HospitalContext, no DB fallback needed.
        Mockito.verifyNoInteractions(assignmentRepository);
    }

    // ── isSuperAdminFromJwtClaim ─────────────────────────────────────

    @Test
    void isSuperAdminFromJwtClaim_returnsTrueOnlyWhenContextSays() {
        // No context set → empty context → false
        assertThat(roleValidator.isSuperAdminFromJwtClaim()).isFalse();

        // Context with superAdmin=false → false (even if authorities have ROLE_SUPER_ADMIN)
        setAuthenticatedUser(UUID.randomUUID(),
            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        HospitalContextHolder.setContext(
            HospitalContext.builder().superAdmin(false).build());
        assertThat(roleValidator.isSuperAdminFromJwtClaim()).isFalse();
        // The deprecated authorities-based check no longer disagrees: it
        // follows the verified signal, so an inflated authority grants nothing.
        assertThat(roleValidator.isSuperAdminFromAuth()).isFalse();

        // Context with superAdmin=true → true
        HospitalContextHolder.setContext(
            HospitalContext.builder().superAdmin(true).build());
        assertThat(roleValidator.isSuperAdminFromJwtClaim()).isTrue();
        assertThat(roleValidator.isSuperAdminFromAuth()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private UUID setAuthenticatedUser(UUID userId, GrantedAuthority... auths) {
        // CustomUserDetails uses a constructor we don't want to depend on
        // here; mock it instead so we get a stable getUserId() value.
        // Use lenient strictness because some tests (the F1 / impersonation
        // cases) populate authorities only and never call getUserId() —
        // strict mode would flag the stub as unnecessary.
        CustomUserDetails principal = Mockito.mock(CustomUserDetails.class);
        Mockito.lenient().when(principal.getUserId()).thenReturn(userId);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, "n/a", List.of(auths));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return userId;
    }

    // ── The two-layer role split: the annotation expands, these do not ──
    // RoleExpansion (E9 #67) makes a physician and a surgeon a doctor on the
    // AUTHORITIES, so the endpoint guards admit them; the checks in this class
    // match the stored ASSIGNMENT code and do not know that. These four pin
    // the two predicates whose callers the co-sign fix touches.

    @Test
    void canCreatePrescription_admitsASurgeonTheEndpointGuardAlreadyAdmits() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), anySet()))
            .thenAnswer(i -> i.<java.util.Set<String>>getArgument(2).contains("SURGEON"));

        assertThat(roleValidator.canCreatePrescription(userId, hospitalId)).isTrue();
    }

    @Test
    void canCreatePrescription_admitsAPhysician() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), anySet()))
            .thenAnswer(i -> i.<java.util.Set<String>>getArgument(2).contains("PHYSICIAN"));

        assertThat(roleValidator.canCreatePrescription(userId, hospitalId)).isTrue();
    }

    @Test
    void canCreatePrescription_stillRefusesARoleThatPrescribesNowhere() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), anySet()))
            .thenAnswer(i -> i.<java.util.Set<String>>getArgument(2).contains("RECEPTIONIST"));

        assertThat(roleValidator.canCreatePrescription(userId, hospitalId)).isFalse();
    }

    @Test
    void canOrderLabTests_admitsASurgeonAndStillRefusesAMidwife() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), anySet()))
            .thenAnswer(i -> i.<java.util.Set<String>>getArgument(2).contains("SURGEON"));
        assertThat(roleValidator.canOrderLabTests(userId, hospitalId)).isTrue();

        Mockito.reset(assignmentRepository);
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), anySet()))
            .thenAnswer(i -> i.<java.util.Set<String>>getArgument(2).contains("MIDWIFE"));
        // Deliberate: the endpoint admits a midwife, but nothing has
        // established that a midwife orders lab tests.
        assertThat(roleValidator.canOrderLabTests(userId, hospitalId)).isFalse();
    }

    private static com.example.hms.model.UserRoleHospitalAssignment assignmentInRole(String code) {
        com.example.hms.model.Role role = new com.example.hms.model.Role();
        role.setCode(code);
        com.example.hms.model.UserRoleHospitalAssignment assignment = new com.example.hms.model.UserRoleHospitalAssignment();
        assignment.setRole(role);
        return assignment;
    }

    @Test
    void isLabOrderingRole_matchesTheRolesCanOrderLabTestsAdmits() {
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("ROLE_DOCTOR"))).isTrue();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("physician"))).isTrue();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("ROLE_SURGEON"))).isTrue();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("NURSE"))).isTrue();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("ROLE_HOSPITAL_ADMIN"))).isFalse();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("ROLE_MIDWIFE"))).isFalse();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole("ROLE_RECEPTIONIST"))).isFalse();
        assertThat(RoleValidator.isLabOrderingRole(assignmentInRole(null))).isFalse();
        assertThat(RoleValidator.isLabOrderingRole(new com.example.hms.model.UserRoleHospitalAssignment())).isFalse();
    }
}
