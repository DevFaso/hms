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
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("kc-user")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roleAssignments != null) {
            jwt.claim(FhirTenantBoundary.CLAIM_ROLE_ASSIGNMENTS, roleAssignments);
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    @Test
    @DisplayName("a super-admin is global")
    void superAdminIsGlobal() {
        assertThat(boundary.holdsRoleAt(ctx(null, true), null, B, FhirTenantBoundary.READ_ROLE_CODES)).isTrue();
        verifyNoInteractions(assignments);
    }

    @Test
    @DisplayName("Keycloak: only the role the claim pairs with THIS hospital counts")
    void keycloakReadsThePairs() {
        JwtAuthenticationToken dual = keycloak(List.of("ROLE_DOCTOR@" + A, "ROLE_RECEPTIONIST@" + B));
        assertThat(boundary.holdsRoleAt(ctx(null, false), dual, A, FhirTenantBoundary.READ_ROLE_CODES)).isTrue();
        assertThat(boundary.holdsRoleAt(ctx(null, false), dual, B, FhirTenantBoundary.READ_ROLE_CODES)).isFalse();
        // Bare and prefixed codes are the same role; case does not matter.
        assertThat(boundary.holdsRoleAt(ctx(null, false), keycloak(List.of("nurse@" + A.toString().toUpperCase())),
            A, FhirTenantBoundary.READ_ROLE_CODES)).isTrue();
        // Malformed entries and a missing claim hold nothing.
        assertThat(boundary.holdsRoleAt(ctx(null, false), keycloak(List.of("ROLE_DOCTOR", "@" + A, "DOCTOR@")),
            A, FhirTenantBoundary.READ_ROLE_CODES)).isFalse();
        assertThat(boundary.holdsRoleAt(ctx(null, false), keycloak(null), A, FhirTenantBoundary.READ_ROLE_CODES))
            .isFalse();
        // A consulting clinician reads but does not write.
        JwtAuthenticationToken radiologist = keycloak(List.of("ROLE_RADIOLOGIST@" + A));
        assertThat(boundary.holdsRoleAt(ctx(null, false), radiologist, A, FhirTenantBoundary.READ_ROLE_CODES)).isTrue();
        assertThat(boundary.holdsRoleAt(ctx(null, false), radiologist, A, FhirTenantBoundary.WRITE_ROLE_CODES))
            .isFalse();
        verifyNoInteractions(assignments);
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
