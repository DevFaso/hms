package com.example.hms.controller.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ControllerAuthUtils}' scope methods are thin adapters over the one
 * tenant resolver (docs/security/tenant-resolution.md §4.1): they answer
 * exactly what {@code RoleValidator.requireActiveHospitalId()} answers, a
 * requested hospital narrows the scope (and is refused, for receptionists
 * too, when the caller does not hold it), and nothing falls back to a
 * "newest" assignment.
 */
class ControllerAuthUtilsScopeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private ControllerAuthUtils authUtils;

    @BeforeEach
    void setUp() {
        authUtils = new ControllerAuthUtils(ActingScopeTestSupport.resolver(assignmentRepository, null));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a clinician's scope is the hospital the request acts at — no assignment query")
    void clinicianTakesTheContextHospital() {
        context(Set.of(HOSPITAL_B), HOSPITAL_B, false, false);

        assertThat(authUtils.resolveHospitalScope(auth("ROLE_NURSE"), null, false)).isEqualTo(HOSPITAL_B);
        verify(assignmentRepository, never()).findAllDetailedByUserId(USER_ID);
    }

    @Test
    @DisplayName("a requested hospital the caller does not hold is refused with 403, not a 400")
    void requestedHospitalIsValidated() {
        context(Set.of(HOSPITAL_A), HOSPITAL_A, false, false);
        Authentication doctor = auth("ROLE_DOCTOR");

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(doctor, HOSPITAL_B, false))
            .isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("with no hospital at all: null when not required; there is no assignment-table fallback")
    void noFallbackToTheAssignmentTable() {
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_NURSE"), null, false)).isNull();
        verify(assignmentRepository, never()).findAllDetailedByUserId(USER_ID);
    }

    @Test
    @DisplayName("several hospitals and none named: refused, never the newest (Q2 A)")
    void ambiguousIsRefused() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER_ID)
            .permittedHospitalIds(Set.of(HOSPITAL_A, HOSPITAL_B))
            .scopeRefusal(ActingScope.Reason.AMBIGUOUS)
            .build());
        Authentication nurse = auth("ROLE_NURSE");

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(nurse, null, false))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a super-admin in global view resolves to null, header-scoped to the header")
    void superAdminGlobalUnlessHeaderScoped() {
        context(Set.of(), null, true, false);
        assertThat(authUtils.currentHospitalId(auth("ROLE_SUPER_ADMIN"))).isNull();
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_SUPER_ADMIN"), null, false)).isNull();

        context(Set.of(), HOSPITAL_A, true, true);
        assertThat(authUtils.currentHospitalId(auth("ROLE_SUPER_ADMIN"))).isEqualTo(HOSPITAL_A);
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_SUPER_ADMIN"), null, false))
            .as("the header scopes resolveHospitalScope too (D1)")
            .isEqualTo(HOSPITAL_A);
    }

    @Test
    @DisplayName("a receptionist follows the request's hospital, and may name another they hold")
    void receptionistFollowsContextOrAHeldRequest() {
        context(Set.of(HOSPITAL_A, HOSPITAL_B), HOSPITAL_A, false, true);
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), null, true)).isEqualTo(HOSPITAL_A);

        context(Set.of(HOSPITAL_A, HOSPITAL_B), HOSPITAL_A, false, true);
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), HOSPITAL_B, true)).isEqualTo(HOSPITAL_B);
    }

    @Test
    @DisplayName("a receptionist naming a hospital they do not hold is refused — no silent substitution (D12)")
    void receptionistUnassignedRequestIsRefused() {
        context(Set.of(HOSPITAL_A), HOSPITAL_A, false, false);
        Authentication receptionist = auth("ROLE_RECEPTIONIST");

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(receptionist, HOSPITAL_B, true))
            .isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("a receptionist with no hospital is refused when scope is required")
    void receptionistWithoutAnyScopeIsRefused() {
        Authentication receptionist = auth("ROLE_RECEPTIONIST");

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(receptionist, null, true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Receptionist must be affiliated");
    }

    @Test
    @DisplayName("the query parameter wins over the body")
    void queryParameterWinsOverBody() {
        context(Set.of(HOSPITAL_A, HOSPITAL_B), HOSPITAL_A, false, true);
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_NURSE"), HOSPITAL_B, HOSPITAL_A, false))
            .isEqualTo(HOSPITAL_B);
    }

    private static void context(Set<UUID> permitted, UUID active, boolean superAdmin, boolean explicit) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER_ID)
            .activeHospitalId(active)
            .permittedHospitalIds(permitted)
            .superAdmin(superAdmin)
            .headerOverridden(explicit)
            .build());
    }

    private static Authentication auth(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("appUserId", USER_ID.toString())
            .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
