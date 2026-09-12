package com.example.hms.controller.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E9 #55 — the controller-side scope resolver reads the request context the
 * security layer built (live permitted set + {@code X-Hospital-Id}), so it
 * answers the same as {@code RoleValidator.requireActiveHospitalId()}. The
 * assignment table is consulted only when no context was populated.
 */
@ExtendWith(MockitoExtension.class)
class ControllerAuthUtilsScopeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks
    private ControllerAuthUtils authUtils;

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a clinician's scope is the context's active hospital — no assignment query")
    void clinicianTakesTheContextHospital() {
        context(HOSPITAL_B, false, false);

        UUID resolved = authUtils.resolveHospitalScope(auth("ROLE_NURSE"), null, false);

        assertThat(resolved).isEqualTo(HOSPITAL_B);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    @DisplayName("a requested hospital is a claim: validated against the caller's assignments")
    void requestedHospitalIsValidated() {
        context(HOSPITAL_A, false, false);
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(false);

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(auth("ROLE_DOCTOR"), HOSPITAL_B, false))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("with no context populated the resolver falls back to the assignment table")
    void fallsBackToAssignmentsWithoutContext() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_A);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        assignment.setActive(true);
        when(assignmentRepository.findAllDetailedByUserId(USER_ID)).thenReturn(List.of(assignment));

        UUID resolved = authUtils.resolveHospitalScope(auth("ROLE_NURSE"), null, false);

        assertThat(resolved).isEqualTo(HOSPITAL_A);
    }

    @Test
    @DisplayName("a super-admin in global view resolves to null, header-scoped to the header")
    void superAdminGlobalUnlessHeaderScoped() {
        context(HOSPITAL_A, true, false);
        assertThat(authUtils.currentHospitalId(auth("ROLE_SUPER_ADMIN"))).isNull();
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_SUPER_ADMIN"), null, false)).isNull();

        context(HOSPITAL_A, true, true);
        assertThat(authUtils.currentHospitalId(auth("ROLE_SUPER_ADMIN"))).isEqualTo(HOSPITAL_A);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    @DisplayName("a receptionist follows the context, and may name another hospital they are assigned to")
    void receptionistFollowsContextOrAnAssignedRequest() {
        context(HOSPITAL_A, false, false);

        assertThat(authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), null, true)).isEqualTo(HOSPITAL_A);

        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(true);
        assertThat(authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), HOSPITAL_B, true)).isEqualTo(HOSPITAL_B);
    }

    @Test
    @DisplayName("a receptionist naming a hospital they are NOT assigned to keeps the context hospital")
    void receptionistUnassignedRequestFallsBackToContext() {
        context(HOSPITAL_A, false, false);
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(false);

        assertThat(authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), HOSPITAL_B, true)).isEqualTo(HOSPITAL_A);
    }

    @Test
    @DisplayName("a receptionist with neither context nor assignment is refused when scope is required")
    void receptionistWithoutAnyScopeIsRefused() {
        when(assignmentRepository.findAllDetailedByUserId(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> authUtils.resolveHospitalScope(auth("ROLE_RECEPTIONIST"), null, true))
            .isInstanceOf(BusinessException.class);
    }

    private static void context(UUID active, boolean superAdmin, boolean headerOverridden) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER_ID)
            .activeHospitalId(active)
            .permittedHospitalIds(Set.of(active))
            .superAdmin(superAdmin)
            .headerOverridden(headerOverridden)
            .build());
    }

    private static Authentication auth(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("uid", USER_ID.toString())
            .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
