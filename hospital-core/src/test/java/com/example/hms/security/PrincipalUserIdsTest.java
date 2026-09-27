package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PrincipalUserIds}: the one rule turning a principal into a local user
 * id (design D10), and never the Keycloak subject.
 */
class PrincipalUserIdsTest {

    @Test
    @DisplayName("password path: the principal's user id")
    void passwordPrincipal() {
        UUID id = UUID.randomUUID();
        HospitalUserDetails details = mock(HospitalUserDetails.class);
        when(details.getUserId()).thenReturn(id);
        assertThat(PrincipalUserIds.of(new UsernamePasswordAuthenticationToken(details, null, List.of())))
            .contains(id);
    }

    @Test
    @DisplayName("Keycloak: appUserId first; a malformed value falls through to the legacy claims")
    void keycloakAppUserId() {
        UUID id = UUID.randomUUID();
        assertThat(PrincipalUserIds.of(jwt("appUserId", id.toString()))).contains(id);
        UUID legacy = UUID.randomUUID();
        Jwt both = Jwt.withTokenValue("t").header("alg", "none")
            .claim("appUserId", "not-a-uuid").claim("uid", legacy.toString()).build();
        assertThat(PrincipalUserIds.of(new JwtAuthenticationToken(both))).contains(legacy);
    }

    @Test
    @DisplayName("the Keycloak subject is never a local id; no principal, no id")
    void neverTheSubject() {
        assertThat(PrincipalUserIds.of(jwt("sub", UUID.randomUUID().toString()))).isEmpty();
        assertThat(PrincipalUserIds.of(null)).isEqualTo(Optional.empty());
        assertThat(PrincipalUserIds.of(new UsernamePasswordAuthenticationToken("name", null))).isEmpty();
    }

    private static JwtAuthenticationToken jwt(String claim, String value) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").claim(claim, value).build());
    }
}
