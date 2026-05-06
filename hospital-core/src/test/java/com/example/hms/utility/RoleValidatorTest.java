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

    // ── helpers ──────────────────────────────────────────────────────

    private UUID setAuthenticatedUser(UUID userId, GrantedAuthority... auths) {
        // CustomUserDetails uses a constructor we don't want to depend on
        // here; mock it instead so we get a stable getUserId() value.
        // The username doesn't matter for any of the paths we exercise,
        // so we don't bother stubbing it.
        CustomUserDetails principal = Mockito.mock(CustomUserDetails.class);
        when(principal.getUserId()).thenReturn(userId);
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
