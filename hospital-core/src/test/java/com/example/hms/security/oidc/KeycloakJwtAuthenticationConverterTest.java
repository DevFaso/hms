package com.example.hms.security.oidc;

import com.example.hms.security.RoleExpansion;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("no client's roles grant anything - an unrelated client's super_admin and doctor least of all")
    void clientRolesGrantNothing() {
        Jwt jwt = jwt(Map.of(
                "sub", "u-1",
                "resource_access", Map.of(
                        "billing-app", Map.of("roles", List.of("super_admin", "doctor")),
                        "hms-portal", Map.of("roles", List.of("ROLE_HOSPITAL_ADMIN")),
                        "hms-backend", Map.of("roles", List.of("audit.view")),
                        "account", Map.of("roles", List.of("manage-account", "view-profile"))
                )
        ));

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("realm roles still map when client roles ride alongside, and only they do")
    void realmRolesMapAlongsideIgnoredClientRoles() {
        Jwt jwt = jwt(Map.of(
                "sub", "u-1",
                "realm_access", Map.of("roles", List.of("doctor", "nurse", "doctor")),
                "resource_access", Map.of("billing-app", Map.of("roles", List.of("super_admin")))
        ));

        assertThat(authorityNames(converter.convert(jwt)))
                .containsExactlyInAnyOrder("ROLE_DOCTOR", "ROLE_NURSE");
    }

    @Test
    @DisplayName("a physician or a surgeon is a doctor over Keycloak, as over the password path")
    void doctorEquivalenceApplies() {
        Jwt physician = jwt(Map.of("sub", "u-1", "realm_access", Map.of("roles", List.of("PHYSICIAN"))));
        Jwt surgeon = jwt(Map.of("sub", "u-2", "realm_access", Map.of("roles", List.of("ROLE_SURGEON"))));

        assertThat(authorityNames(converter.convert(physician))).containsExactlyInAnyOrder("ROLE_PHYSICIAN", "ROLE_DOCTOR");
        assertThat(authorityNames(converter.convert(surgeon))).containsExactlyInAnyOrder("ROLE_SURGEON", "ROLE_DOCTOR");
    }

    @Test
    @DisplayName("a super-admin inherits exactly RoleExpansion's list")
    void superAdminInheritsTheOneList() {
        Jwt jwt = jwt(Map.of("sub", "u-1", "realm_access", Map.of("roles", List.of("super_admin"))));

        assertThat(converter.convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyElementsOf(RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN")));
    }

    @Test
    @DisplayName("Keycloak's stock realm roles pass through as they always have, and widen nothing")
    void stockRealmRolesPassThrough() {
        Jwt jwt = jwt(Map.of("sub", "u-1", "realm_access",
                Map.of("roles", List.of("PATIENT", "offline_access", "uma_authorization", "default-roles-hms"))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactlyInAnyOrder(
                "ROLE_PATIENT", "ROLE_OFFLINE_ACCESS", "ROLE_UMA_AUTHORIZATION", "ROLE_DEFAULT-ROLES-HMS");
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
