package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PrincipalUserIds}: the one rule turning a principal into a local user
 * id (design D10). A Keycloak principal's id is the link the context filter
 * verified, never a claim read on its own and never the subject.
 */
class PrincipalUserIdsTest {

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

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
    @DisplayName("Keycloak: the id the filter linked for this principal")
    void keycloakLinkedId() {
        UUID id = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(id).principalUsername("dr.a").build());
        assertThat(PrincipalUserIds.of(jwt("dr.a", "appUserId", id.toString()))).contains(id);
    }

    @Test
    @DisplayName("Keycloak: an appUserId claim the filter did not link is no id (a mis-set attribute borrows nothing)")
    void keycloakUnlinkedClaimIsNoId() {
        UUID claimed = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder().principalUsername("dr.a").build());
        assertThat(PrincipalUserIds.of(jwt("dr.a", "appUserId", claimed.toString()))).isEmpty();
        HospitalContextHolder.clear();
        assertThat(PrincipalUserIds.of(jwt("dr.a", "appUserId", claimed.toString())))
            .as("no context at all").isEmpty();
        assertThat(PrincipalUserIds.of(jwt("dr.a", "uid", claimed.toString())))
            .as("the legacy claims are not read").isEmpty();
    }

    @Test
    @DisplayName("Keycloak: a context built for another principal is not this token's link")
    void keycloakContextOfAnotherPrincipal() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID()).principalUsername("someone.else").build());
        assertThat(PrincipalUserIds.of(jwt("dr.a", "appUserId", UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    @DisplayName("no principal, or an unknown principal type, has no id")
    void noPrincipal() {
        assertThat(PrincipalUserIds.of(null)).isEmpty();
        assertThat(PrincipalUserIds.of(new UsernamePasswordAuthenticationToken("name", null))).isEmpty();
    }

    private static JwtAuthenticationToken jwt(String subject, String claim, String value) {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(subject).claim(claim, value).build();
        return new JwtAuthenticationToken(token, List.of(), subject);
    }
}
