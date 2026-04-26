package com.example.hms.security.oidc;

import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link KeycloakHospitalContextResolver}. Exercises the
 * claim shapes the {@code hms-claims} client scope can emit (single
 * {@code hospital_id}, list of {@code "ROLE@hospital-uuid"} role
 * assignments) plus defensive behaviour for malformed entries.
 */
class KeycloakHospitalContextResolverTest {

    private final KeycloakHospitalContextResolver resolver = new KeycloakHospitalContextResolver();

    @Test
    void resolvesPermittedHospitalsFromHospitalIdAndRoleAssignments() {
        UUID primary = UUID.randomUUID();
        UUID secondary = UUID.randomUUID();

        Jwt jwt = jwt(claims -> {
            claims.put("preferred_username", "dr.alice");
            claims.put("hospital_id", primary.toString());
            claims.put("role_assignments", List.of(
                    "DOCTOR@" + primary,
                    "DOCTOR@" + secondary));
        });

        HospitalContext ctx = resolver.resolve(jwt, List.of(authority("ROLE_DOCTOR")));

        assertThat(ctx.getPrincipalUsername()).isEqualTo("dr.alice");
        assertThat(ctx.getActiveHospitalId()).isEqualTo(primary);
        assertThat(ctx.getPermittedHospitalIds()).containsExactlyInAnyOrder(primary, secondary);
        assertThat(ctx.isSuperAdmin()).isFalse();
        assertThat(ctx.isHospitalAdmin()).isFalse();
    }

    @Test
    void emptyClaimsYieldEmptyContext() {
        Jwt jwt = jwt(claims -> { /* no hospital_id, no role_assignments */ });

        HospitalContext ctx = resolver.resolve(jwt, List.of(authority("ROLE_DOCTOR")));

        assertThat(ctx.getActiveHospitalId()).isNull();
        assertThat(ctx.getPermittedHospitalIds()).isEmpty();
        assertThat(ctx.isSuperAdmin()).isFalse();
        assertThat(ctx.isHospitalAdmin()).isFalse();
    }

    @Test
    void malformedRoleAssignmentEntriesAreSkippedNotThrown() {
        UUID valid = UUID.randomUUID();

        Jwt jwt = jwt(claims -> claims.put("role_assignments", List.of(
                "DOCTOR@" + valid,
                "missing-delimiter",
                "@trailing-only",
                "DOCTOR@",
                "DOCTOR@not-a-uuid")));

        HospitalContext ctx = resolver.resolve(jwt, List.of());

        assertThat(ctx.getPermittedHospitalIds()).containsExactly(valid);
    }

    @Test
    void superAdminAndHospitalAdminAuthoritiesPropagate() {
        Jwt jwt = jwt(claims -> { });

        Set<GrantedAuthority> superAuth = Set.of(authority(ROLE_SUPER_ADMIN));
        Set<GrantedAuthority> hospitalAuth = Set.of(authority(ROLE_HOSPITAL_ADMIN));

        assertThat(resolver.resolve(jwt, superAuth).isSuperAdmin()).isTrue();
        assertThat(resolver.resolve(jwt, superAuth).isHospitalAdmin()).isFalse();
        assertThat(resolver.resolve(jwt, hospitalAuth).isHospitalAdmin()).isTrue();
        assertThat(resolver.resolve(jwt, hospitalAuth).isSuperAdmin()).isFalse();
    }

    @Test
    void nonUuidHospitalIdIsIgnored() {
        Jwt jwt = jwt(claims -> claims.put("hospital_id", "h-1"));

        HospitalContext ctx = resolver.resolve(jwt, List.of());

        assertThat(ctx.getActiveHospitalId()).isNull();
        assertThat(ctx.getPermittedHospitalIds()).isEmpty();
    }

    private static GrantedAuthority authority(String role) {
        return new SimpleGrantedAuthority(role);
    }

    private static Jwt jwt(java.util.function.Consumer<Map<String, Object>> claimMutator) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", UUID.randomUUID().toString());
        claimMutator.accept(claims);
        Instant now = Instant.now();
        return new Jwt("token", now, now.plusSeconds(60), headers, claims);
    }
}
