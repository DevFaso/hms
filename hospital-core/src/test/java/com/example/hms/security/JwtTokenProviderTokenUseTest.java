package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One key signs every token this issuer mints, so what a token is FOR is in
 * its claims: only a refresh token is accepted at {@code /auth/token/refresh},
 * and the MFA challenge token authenticates with one marker authority and no
 * role.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTokenUseTest {

    private static final String SECRET = "token-use-test-secret-minimum-256-bits-long-for-hmac-sha!!";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USERNAME = "admin.token.use";

    @Mock private HospitalUserDetailsService userDetailsService;
    @Mock private TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = provider(SECRET);
    }

    private JwtTokenProvider provider(String secret) {
        JwtTokenProvider p = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
        ReflectionTestUtils.setField(p, "jwtSecret", secret);
        ReflectionTestUtils.setField(p, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(p, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(p, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(p, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(p, "previousPublicKeyPem", "");
        p.init();
        return p;
    }

    private static TokenUserDescriptor descriptor() {
        return new TokenUserDescriptor(USER_ID, USERNAME, List.of("ROLE_HOSPITAL_ADMIN"));
    }

    @Test
    @DisplayName("a refresh token from either mint path is a refresh token")
    void refreshTokensAreRefreshTokens() {
        HospitalUserDetails principal = mock(HospitalUserDetails.class);
        when(principal.getUsername()).thenReturn(USERNAME);
        when(principal.getAuthorities()).thenAnswer(inv -> List.<GrantedAuthority>of(() -> "ROLE_HOSPITAL_ADMIN"));
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        assertThat(provider.isRefreshToken(provider.generateRefreshToken(descriptor()))).isTrue();
        assertThat(provider.isRefreshToken(provider.generateRefreshToken(authentication))).isTrue();
    }

    @Test
    @DisplayName("an access, impersonation or MFA challenge token is not a refresh token, though the same key signs it")
    void otherTokensAreNotRefreshTokens() {
        String access = provider.generateAccessToken(descriptor());
        String impersonation = provider.generateImpersonationAccessToken(
            descriptor(), UUID.randomUUID(), "super.admin", 1_800_000L);
        String challenge = provider.generateMfaToken(USERNAME);

        assertThat(provider.validateToken(access)).isTrue();
        assertThat(provider.validateToken(impersonation)).isTrue();
        assertThat(provider.validateToken(challenge)).isTrue();
        assertThat(provider.isRefreshToken(access)).isFalse();
        assertThat(provider.isRefreshToken(impersonation)).isFalse();
        assertThat(provider.isRefreshToken(challenge)).isFalse();
    }

    @Test
    @DisplayName("a refresh token signed with another key, or no token at all, is not a refresh token")
    void forgedOrMissingIsNotARefreshToken() {
        String foreign = provider("another-secret-entirely-minimum-256-bits-long-for-hmac-sha!!")
            .generateRefreshToken(descriptor());

        assertThat(provider.isRefreshToken(foreign)).isFalse();
        assertThat(provider.isRefreshToken(null)).isFalse();
        assertThat(provider.isRefreshToken("")).isFalse();
        assertThat(provider.isRefreshToken("not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("the MFA challenge token authenticates with the challenge marker and no role")
    void challengeTokenCarriesOnlyTheMarker() {
        HospitalUserDetails principal = mock(HospitalUserDetails.class);
        when(principal.isEnabled()).thenReturn(true);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(principal);

        Authentication authentication = provider.getAuthenticationFromJwt(provider.generateMfaToken(USERNAME));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly(MfaChallengeScope.AUTHORITY);
        assertThat(MfaChallengeScope.isChallengeSession(authentication)).isTrue();
    }

    @Test
    @DisplayName("an access token is never mistaken for a challenge session")
    void accessTokenIsNoChallengeSession() {
        HospitalUserDetails principal = mock(HospitalUserDetails.class);
        when(principal.isEnabled()).thenReturn(true);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(principal);

        Authentication authentication = provider.getAuthenticationFromJwt(provider.generateAccessToken(descriptor()));

        assertThat(MfaChallengeScope.isChallengeSession(authentication)).isFalse();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .contains("ROLE_HOSPITAL_ADMIN");
    }

    // ── Refresh tokens minted before the mark, and refresh tokens as bearers ──

    /** A refresh token exactly as this issuer minted it before token_use existed. */
    private static String legacyRefreshToken(long expiresInMs) {
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(USERNAME)
            .claim("roles", List.of("ROLE_HOSPITAL_ADMIN"))
            .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
            .expiration(new Date(System.currentTimeMillis() + expiresInMs))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    private static String legacyRefreshToken() {
        return legacyRefreshToken(3_600_000L);
    }

    @Test
    @DisplayName("a legacy refresh token is accepted up to its own expiry, and not after")
    void legacyRefreshTokenUpToItsOwnExpiry() {
        assertThat(provider.isRefreshToken(legacyRefreshToken())).isTrue();
        assertThat(provider.isRefreshToken(legacyRefreshToken(-1_000L))).isFalse();
    }

    @Test
    @DisplayName("an access, impersonation or challenge token is never mistaken for the legacy shape")
    void accessTokenIsNeverTheLegacyShape() {
        assertThat(provider.isRefreshToken(provider.generateAccessToken(descriptor()))).isFalse();
        assertThat(provider.isRefreshToken(provider.generateImpersonationAccessToken(
            descriptor(), UUID.randomUUID(), "super.admin", 1_800_000L))).isFalse();
        assertThat(provider.isRefreshToken(provider.generateMfaToken(USERNAME))).isFalse();
    }

    @Test
    @DisplayName("a refresh token, marked or legacy, is not an access token")
    void refreshTokenIsNoBearer() {
        String marked = provider.generateRefreshToken(descriptor());
        String legacy = legacyRefreshToken();

        assertThatThrownBy(() -> provider.getAuthenticationFromJwt(marked))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.getAuthenticationFromJwt(legacy))
            .isInstanceOf(BadCredentialsException.class);
    }
}
