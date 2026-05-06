package com.example.hms.utility;

import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
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
 * {@link UserRoleHospitalAssignment} and that assignment is
 * <b>global</b> (no hospital attached), the code did
 * {@code .getHospital().getId()} on a null reference. This used to be
 * unreachable because super-admins always had {@code X-Hospital-Id}
 * set, but the cross-tenant "global view" deliberately omits the
 * header — so the fallback path now fires and used to crash 10
 * dashboard endpoints simultaneously.
 */
@ExtendWith(MockitoExtension.class)
class RoleValidatorTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    private RoleValidator roleValidator;

    @BeforeEach
    void setUp() {
        roleValidator = new RoleValidator(assignmentRepository);
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

    @Test
    void getCurrentHospitalId_returnsNullWhenNoActiveAssignments() {
        UUID userId = setAuthenticatedUser(UUID.randomUUID());
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId)).thenReturn(List.of());

        assertThat(roleValidator.getCurrentHospitalId()).isNull();
    }

    @Test
    void getCurrentHospitalId_returnsNullWhenMultipleActiveAssignments() {
        UUID userId = setAuthenticatedUser(UUID.randomUUID());
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId))
            .thenReturn(List.of(scopedAssignment(), scopedAssignment()));

        assertThat(roleValidator.getCurrentHospitalId()).isNull();
    }

    @Test
    void getCurrentHospitalId_returnsHospitalIdForSingleScopedAssignment() {
        UUID userId = setAuthenticatedUser(UUID.randomUUID());
        UUID hospitalId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = scopedAssignment(hospitalId);
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId))
            .thenReturn(List.of(assignment));

        assertThat(roleValidator.getCurrentHospitalId()).isEqualTo(hospitalId);
    }

    /**
     * THE regression. Before the fix this NPE'd; after the fix it
     * returns null so {@link RoleValidator#requireActiveHospitalId()}
     * can fall through to its super-admin branch.
     */
    @Test
    void getCurrentHospitalId_returnsNullForSingleGlobalAssignment() {
        UUID userId = setAuthenticatedUser(UUID.randomUUID());
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId))
            .thenReturn(List.of(globalAssignment()));

        assertThat(roleValidator.getCurrentHospitalId()).isNull();
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
     * The end-to-end case the cross-tenant slice hits in production:
     * super-admin in global view (no X-Hospital-Id header so
     * HospitalContext.activeHospitalId is null) AND the user has a
     * single global assignment. Before the fix this NPE'd at
     * getCurrentHospitalId line 95; after the fix the method returns
     * null and lets the unscoped findAll path run.
     */
    @Test
    void requireActiveHospitalId_returnsNullForSuperAdminWithGlobalAssignment() {
        UUID userId = setAuthenticatedUser(UUID.randomUUID(),
            new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId))
            .thenReturn(List.of(globalAssignment()));

        assertThat(roleValidator.requireActiveHospitalId()).isNull();
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
     * to {@code null} regardless of any JWT-derived activeHospitalId.
     */
    @Test
    void requireActiveHospitalId_returnsNullForSuperAdmin_evenWhenJwtPopulatesActiveHospitalId() {
        // Real super-admin: JWT claim says so, AND JwtTokenProvider
        // pre-populated activeHospitalId from CLAIM_PRIMARY_HOSPITAL_ID.
        HospitalContextHolder.setContext(
            HospitalContext.builder()
                .superAdmin(true)
                .activeHospitalId(UUID.randomUUID()) // primary hospital from JWT
                .build());

        assertThat(roleValidator.requireActiveHospitalId()).isNull();
        // Resolved entirely from HospitalContext — no DB lookup needed.
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
        // Authorities-based check disagrees — proving the two are independent.
        assertThat(roleValidator.isSuperAdminFromAuth()).isTrue();

        // Context with superAdmin=true → true
        HospitalContextHolder.setContext(
            HospitalContext.builder().superAdmin(true).build());
        assertThat(roleValidator.isSuperAdminFromJwtClaim()).isTrue();
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

    private UserRoleHospitalAssignment scopedAssignment() {
        return scopedAssignment(UUID.randomUUID());
    }

    private UserRoleHospitalAssignment scopedAssignment(UUID hospitalId) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setHospital(h);
        return a;
    }

    private UserRoleHospitalAssignment globalAssignment() {
        // Hospital deliberately left null — represents a SUPER_ADMIN
        // role assigned without a tenant scope.
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setHospital(null);
        return a;
    }
}
