package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Behaviour test for {@link JwtTokenProvider#getAuthenticationFromJwt(String)} —
 * specifically the email-verification gate that rejects JWTs whose subject
 * resolves to a disabled / unverified {@link HospitalUserDetails}.
 *
 * <p>The login endpoint already throws {@link DisabledException} via Spring's
 * {@code DaoAuthenticationProvider}; this test guards the parallel JWT path so
 * a token issued through any other code path (cookie refresh, MFA exchange,
 * cross-service handoff) cannot grant role-based access to an inactive account.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderAuthenticationTest {

    @Mock
    private HospitalUserDetailsService userDetailsService;

    @Mock
    private com.example.hms.security.auth.TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
        // HMAC mode is plenty for getAuthenticationFromJwt — the asymmetric path is
        // exercised in JwtTokenProviderAsymmetricTest.
        ReflectionTestUtils.setField(provider, "jwtSecret",
            "dev-secret-change-me-in-production-minimum-256-bits-long!!");
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");
        provider.init();
    }

    @Test
    @DisplayName("getAuthenticationFromJwt rejects token for a disabled user with DisabledException")
    void rejectsDisabledUser() {
        UUID userId = UUID.randomUUID();
        String username = "unverified.patient";
        TokenUserDescriptor descriptor = new TokenUserDescriptor(
            userId, username, List.of("ROLE_PATIENT"));

        // A signed, valid JWT bound to this username
        String token = provider.generateAccessToken(descriptor);
        assertThat(token).isNotBlank();

        when(userDetailsService.loadUserByUsername(username))
            .thenReturn(new StubHospitalUserDetails(userId, username, /*enabled=*/ false,
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT"))));

        assertThatThrownBy(() -> provider.getAuthenticationFromJwt(token))
            .isInstanceOf(DisabledException.class)
            .hasMessageContaining(username);
    }

    @Test
    @DisplayName("getAuthenticationFromJwt returns Authentication for an enabled user")
    void enabledUserAuthenticatesNormally() {
        UUID userId = UUID.randomUUID();
        String username = "verified.doctor";
        TokenUserDescriptor descriptor = new TokenUserDescriptor(
            userId, username, List.of("ROLE_DOCTOR"));

        String token = provider.generateAccessToken(descriptor);

        StubHospitalUserDetails details = new StubHospitalUserDetails(
            userId, username, /*enabled=*/ true,
            List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
        when(userDetailsService.loadUserByUsername(username)).thenReturn(details);
        // tenantRoleAssignmentAccessor isn't consulted on the happy path because the
        // descriptor already supplied roles; declared lenient just in case the impl
        // changes to fall back to tenant lookup.
        lenient().when(tenantRoleAssignmentAccessor.findAssignmentsForUser(userId))
            .thenReturn(List.of());

        Authentication auth = provider.getAuthenticationFromJwt(token);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo(username);
        assertThat(auth.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .contains("ROLE_DOCTOR");
        assertThat(auth.getPrincipal()).isSameAs(details);
    }

    /**
     * Minimal HospitalUserDetails stub. Building the full Spring Security UserDetails
     * surface as a Mockito mock is unwieldy — Mockito would have to stub each of the
     * eight UserDetails methods, and the relevant flag (isEnabled) needs to flip
     * between cases. A typed test double makes the contract explicit.
     */
    private record StubHospitalUserDetails(
        UUID userId,
        String username,
        boolean enabled,
        Collection<? extends GrantedAuthority> authorities
    ) implements HospitalUserDetails {

        @Override public UUID getUserId() { return userId; }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
        @Override public String getPassword() { return "irrelevant"; }
        @Override public String getUsername() { return username; }
        @Override public boolean isAccountNonExpired() { return true; }
        @Override public boolean isAccountNonLocked() { return true; }
        @Override public boolean isCredentialsNonExpired() { return true; }
        @Override public boolean isEnabled() { return enabled; }
    }
}
