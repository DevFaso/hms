package com.example.hms.fhir;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-hospital role check on both principal shapes. The authorities a
 * path matcher sees are the union across hospitals; this is the question
 * asked about ONE hospital.
 */
class FhirTenantBoundaryRoleTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final FhirTenantBoundary boundary = new FhirTenantBoundary(assignments);

    private static HospitalContext ctx(UUID userId, boolean superAdmin) {
        return HospitalContext.builder().principalUserId(userId).activeHospitalId(A)
            .permittedHospitalIds(Set.of(A, B)).superAdmin(superAdmin).build();
    }

    private static JwtAuthenticationToken keycloak(List<String> roleAssignments) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("kc-user")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
            .claim("role_assignments", roleAssignments)
            .build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    @DisplayName("a super-admin is global")
    void superAdminIsGlobal() {
        assertThat(boundary.holdsRoleAt(ctx(null, true), null, B, FhirTenantBoundary.READ_ROLE_CODES)).isTrue();
        verifyNoInteractions(assignments);
    }

    @Test
    @DisplayName("Keycloak: the role_assignments claim grants nothing; the linked account's live assignment decides")
    void keycloakReadsTheLiveAssignmentsToo() {
        JwtAuthenticationToken claimsDoctorAtB = keycloak(List.of("ROLE_DOCTOR@" + B));
        // No linked local account (principalUserId null): the claim alone holds nothing.
        assertThat(boundary.holdsRoleAt(ctx(null, false), claimsDoctorAtB, B, FhirTenantBoundary.READ_ROLE_CODES))
            .isFalse();
        verifyNoInteractions(assignments);

        // Linked: the table answers, whatever the claim says.
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(USER), eq(B), any())).thenReturn(false);
        assertThat(boundary.holdsRoleAt(ctx(USER, false), claimsDoctorAtB, B, FhirTenantBoundary.READ_ROLE_CODES))
            .as("a role the claim asserts but the table no longer holds does not count")
            .isFalse();
        verify(assignments).existsActiveByUserAndHospitalAndAnyRoleCode(eq(USER), eq(B), any());
    }

    @Test
    @DisplayName("HMS token: the live assignment at THIS hospital, in either stored form")
    void hmsTokenReadsTheLiveAssignments() {
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(USER), eq(A), any())).thenReturn(true);
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(USER), eq(B), any())).thenReturn(false);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u", "p", List.of());

        assertThat(boundary.holdsRoleAt(ctx(USER, false), auth, A, FhirTenantBoundary.WRITE_ROLE_CODES)).isTrue();
        assertThat(boundary.holdsRoleAt(ctx(USER, false), auth, B, FhirTenantBoundary.WRITE_ROLE_CODES)).isFalse();
        verify(assignments).existsActiveByUserAndHospitalAndAnyRoleCode(USER, A, Set.of(
            "DOCTOR", "ROLE_DOCTOR", "PHYSICIAN", "ROLE_PHYSICIAN", "SURGEON", "ROLE_SURGEON",
            "NURSE", "ROLE_NURSE", "MIDWIFE", "ROLE_MIDWIFE"));
    }

    @Test
    @DisplayName("no principal user, no context or no hospital holds nothing")
    void nothingToAskAbout() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u", "p", List.of());
        assertThat(boundary.holdsRoleAt(ctx(null, false), auth, A, FhirTenantBoundary.READ_ROLE_CODES)).isFalse();
        assertThat(boundary.holdsRoleAt(null, auth, A, FhirTenantBoundary.READ_ROLE_CODES)).isFalse();
        assertThat(boundary.holdsRoleAt(ctx(USER, false), auth, null, FhirTenantBoundary.READ_ROLE_CODES)).isFalse();
        verifyNoInteractions(assignments);
    }
}
