package com.example.hms.security.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmAccessRolesToRolePrefixedAuthorities() {
        Jwt jwt = jwt(Map.of(
                "preferred_username", "alice",
                "realm_access", Map.of("roles", List.of("DOCTOR", "NURSE"))
        ));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(authorityNames(token)).containsExactlyInAnyOrder("ROLE_DOCTOR", "ROLE_NURSE");
        assertThat(token.getName()).isEqualTo("alice");
    }

    @Test
    void preservesAlreadyPrefixedRoles() {
        Jwt jwt = jwt(Map.of(
                "sub", "u-1",
                "realm_access", Map.of("roles", List.of("ROLE_HOSPITAL_ADMIN"))
        ));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_HOSPITAL_ADMIN");
    }

    @Test
    void mergesRealmAndClientRolesAndDeduplicates() {
        Jwt jwt = jwt(Map.of(
                "sub", "u-1",
                "realm_access", Map.of("roles", List.of("doctor", "nurse")),
                "resource_access", Map.of(
                        "hms-portal", Map.of("roles", List.of("doctor", "patient")),
                        "hms-api", Map.of("roles", List.of("staff"))
                )
        ));

        assertThat(authorityNames(converter.convert(jwt)))
                .containsExactlyInAnyOrder("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_PATIENT", "ROLE_STAFF");
    }

    @Test
    void returnsEmptyAuthoritiesWhenClaimsMissing() {
        Jwt jwt = jwt(Map.of("sub", "u-1"));

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    void ignoresMalformedRealmAccessClaim() {
        Jwt jwt = jwt(Map.of(
                "sub", "u-1",
                "realm_access", "not-a-map"
        ));

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    void prefersPreferredUsernameThenEmailThenSubject() {
        Jwt withUsername = jwt(Map.of("sub", "u-1", "preferred_username", "alice", "email", "a@b.c"));
        Jwt withEmail = jwt(Map.of("sub", "u-2", "email", "b@c.d"));
        Jwt subjectOnly = jwt(Map.of("sub", "u-3"));

        assertThat(converter.convert(withUsername).getName()).isEqualTo("alice");
        assertThat(converter.convert(withEmail).getName()).isEqualTo("b@c.d");
        assertThat(converter.convert(subjectOnly).getName()).isEqualTo("u-3");
    }

    @Test
    void normaliseRoleNameUppercasesAndPrefixes() {
        assertThat(converter.normaliseRoleName("doctor")).isEqualTo("ROLE_DOCTOR");
        assertThat(converter.normaliseRoleName("ROLE_doctor")).isEqualTo("ROLE_DOCTOR");
        assertThat(converter.normaliseRoleName("Nurse")).isEqualTo("ROLE_NURSE");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(c -> c.putAll(claims))
                .build();
    }

    private static Set<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
