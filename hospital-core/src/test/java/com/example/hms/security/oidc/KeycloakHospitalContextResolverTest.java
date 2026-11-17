package com.example.hms.security.oidc;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KeycloakHospitalContextResolver}: the Keycloak path builds its context
 * from the SAME live computation as the password path, keyed on the local
 * account the {@code appUserId} claim names (docs/security/tenant-resolution.md
 * §3.2). The token's hospital claims are no longer inputs.
 */
class KeycloakHospitalContextResolverTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TenantRoleAssignmentAccessor assignments = mock(TenantRoleAssignmentAccessor.class);
    @SuppressWarnings("unchecked")
    private final ActingScopeResolver actingScopeResolver = new ActingScopeResolver(assignments,
        mock(UserRoleHospitalAssignmentRepository.class), mock(ObjectProvider.class));
    private final KeycloakHospitalContextResolver resolver =
        new KeycloakHospitalContextResolver(userRepository, actingScopeResolver);

    @Test
    @DisplayName("appUserId names the local account; its LIVE assignments are the scope, not the token's claims")
    void liveAssignmentsNotClaims() {
        localUser("dr.alice", "alice@example.com");
        when(assignments.findAssignmentsForUser(USER_ID)).thenReturn(List.of(
            new TenantRoleAssignment(HOSPITAL_A, null, "ROLE_DOCTOR", "ROLE_DOCTOR", true, FacilityType.HOSPITAL)));

        HospitalContext ctx = resolver.resolve(jwt(claims -> {
            claims.put("appUserId", USER_ID.toString());
            // Stale claims naming a hospital the table no longer grants: ignored.
            claims.put("hospital_id", HOSPITAL_B.toString());
            claims.put("role_assignments", List.of("ROLE_DOCTOR@" + HOSPITAL_B));
        }), "dr.alice");

        assertThat(ctx.getPrincipalUserId()).isEqualTo(USER_ID);
        assertThat(ctx.getPermittedHospitalIds()).containsExactly(HOSPITAL_A);
        assertThat(ctx.getActiveHospitalId()).isEqualTo(HOSPITAL_A);
        assertThat(ActingScopeResolver.scopeOf(ctx))
            .isEqualTo(new ActingScope.Pinned(HOSPITAL_A, ActingScope.Source.SOLE_ASSIGNMENT));
    }

    @Test
    @DisplayName("the principal may be named by email: the link still holds")
    void emailPrincipalLinks() {
        localUser("dr.alice", "alice@example.com");
        when(assignments.findAssignmentsForUser(USER_ID)).thenReturn(List.of());

        HospitalContext ctx = resolver.resolve(jwt(claims -> claims.put("appUserId", USER_ID.toString())),
            "ALICE@example.com");

        assertThat(ctx.getPrincipalUserId()).isEqualTo(USER_ID);
        assertThat(ctx.getScopeRefusal()).isEqualTo(ActingScope.Reason.NO_HOSPITAL);
    }

    @Test
    @DisplayName("no appUserId claim: NO_LOCAL_USER, whatever the hospital claims say, and no fallback to the username")
    void noAppUserIdIsUnlinked() {
        HospitalContext ctx = resolver.resolve(jwt(claims -> {
            claims.put("hospital_id", HOSPITAL_A.toString());
            claims.put("role_assignments", List.of("ROLE_DOCTOR@" + HOSPITAL_A));
        }), "dr.alice");

        assertUnlinked(ctx);
        verify(userRepository, never()).findById(any());
        verify(assignments, never()).findAssignmentsForUser(any());
    }

    @Test
    @DisplayName("an appUserId naming another account (username and email differ) is unlinked")
    void appUserIdOfAnotherAccountIsUnlinked() {
        localUser("dr.bob", "bob@example.com");

        HospitalContext ctx = resolver.resolve(jwt(claims -> claims.put("appUserId", USER_ID.toString())),
            "dr.alice");

        assertUnlinked(ctx);
        verify(assignments, never()).findAssignmentsForUser(any());
    }

    @Test
    @DisplayName("an appUserId that is not a UUID, matches no row, or names a deleted account is unlinked")
    void unusableAppUserIdIsUnlinked() {
        assertUnlinked(resolver.resolve(jwt(claims -> claims.put("appUserId", "not-a-uuid")), "dr.alice"));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertUnlinked(resolver.resolve(jwt(claims -> claims.put("appUserId", USER_ID.toString())), "dr.alice"));

        User deleted = localUser("dr.alice", "alice@example.com");
        deleted.setDeleted(true);
        assertUnlinked(resolver.resolve(jwt(claims -> claims.put("appUserId", USER_ID.toString())), "dr.alice"));
    }

    @Test
    @DisplayName("a SUPER_ADMIN realm role grants nothing; a live SUPER_ADMIN assignment is global view")
    void superAdminComesFromTheTable() {
        localUser("root", "root@example.com");
        when(assignments.findAssignmentsForUser(USER_ID)).thenReturn(List.of(
            new TenantRoleAssignment(null, null, "ROLE_SUPER_ADMIN", "ROLE_SUPER_ADMIN", true, null),
            new TenantRoleAssignment(HOSPITAL_A, null, "ROLE_DOCTOR", "ROLE_DOCTOR", true, FacilityType.HOSPITAL)));

        HospitalContext ctx = resolver.resolve(jwt(claims -> claims.put("appUserId", USER_ID.toString())), "root");

        assertThat(ctx.isSuperAdmin()).isTrue();
        assertThat(ActingScopeResolver.scopeOf(ctx)).isEqualTo(new ActingScope.Global(USER_ID));
    }

    private User localUser(String username, String email) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername(username);
        user.setEmail(email);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private static void assertUnlinked(HospitalContext ctx) {
        assertThat(ctx.getPrincipalUserId()).isNull();
        assertThat(ctx.getPermittedHospitalIds()).isEmpty();
        assertThat(ctx.isSuperAdmin()).isFalse();
        assertThat(ctx.getScopeRefusal()).isEqualTo(ActingScope.Reason.NO_LOCAL_USER);
    }

    private static Jwt jwt(Consumer<Map<String, Object>> claimMutator) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", UUID.randomUUID().toString());
        claimMutator.accept(claims);
        Instant now = Instant.now();
        return new Jwt("token", now, now.plusSeconds(60), headers, claims);
    }
}
