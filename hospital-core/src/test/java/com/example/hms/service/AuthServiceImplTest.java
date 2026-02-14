package com.example.hms.service;

import com.example.hms.exception.UnauthorizedException;
import com.example.hms.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private final AuthServiceImpl authService = new AuthServiceImpl();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUserId_returnsId_whenAuthenticated() {
        UUID userId = UUID.randomUUID();
        CustomUserDetails userDetails = mock(CustomUserDetails.class);
        when(userDetails.getUserId()).thenReturn(userId);
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, "token", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(authService.getCurrentUserId()).isEqualTo(userId);
    }

    @Test
    void getCurrentUserId_throwsUnauthorized_whenNoAuth() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(authService::getCurrentUserId)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUserId_throwsUnauthorized_whenPrincipalNotString() {
        Authentication auth = new UsernamePasswordAuthenticationToken("stringPrincipal", "token", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(authService::getCurrentUserId)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUserToken_returnsToken_whenCredentialsIsString() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "my-jwt-token", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(authService.getCurrentUserToken()).isEqualTo("my-jwt-token");
    }

    @Test
    void getCurrentUserToken_throwsUnauthorized_whenNoAuth() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(authService::getCurrentUserToken)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUserToken_throwsUnauthorized_whenCredentialsNull() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(authService::getCurrentUserToken)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUserToken_throwsUnauthorized_whenCredentialsBlank() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "  ", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(authService::getCurrentUserToken)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUserToken_returnsToString_whenCredentialsIsNonStringObject() {
        Object creds = new Object() {
            @Override
            public String toString() { return "object-token"; }
        };
        Authentication auth = new UsernamePasswordAuthenticationToken("user", creds, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(authService.getCurrentUserToken()).isEqualTo("object-token");
    }

    @Test
    void hasRole_returnsTrue_whenUserHasRole() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "token", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(authService.hasRole("ROLE_ADMIN")).isTrue();
    }

    @Test
    void hasRole_returnsFalse_whenUserDoesNotHaveRole() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "token", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(authService.hasRole("ROLE_ADMIN")).isFalse();
    }

    @Test
    void hasRole_returnsFalse_whenNoAuth() {
        SecurityContextHolder.clearContext();
        assertThat(authService.hasRole("ROLE_ADMIN")).isFalse();
    }
}
