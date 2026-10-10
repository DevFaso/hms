package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The legacy issuer's second-factor proof (provider plan AC-13): only the
 * tokens minted after a verified TOTP code carry {@code amr: ["pwd", "otp"]},
 * a refresh token passes it on, and nothing else (a password-only token, the
 * MFA challenge token, a tampered or foreign token) reads as proof.
 */
class JwtTokenProviderSecondFactorTest {

    private static final TokenUserDescriptor PHARMACIST =
        new TokenUserDescriptor(UUID.randomUUID(), "pharm.one", List.of("ROLE_PHARMACIST"));

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = provider("dev-secret-change-me-in-production-minimum-256-bits-long!!");
    }

    private static JwtTokenProvider provider(String secret) {
        JwtTokenProvider tokens = new JwtTokenProvider(mock(HospitalUserDetailsService.class),
            mock(TenantRoleAssignmentAccessor.class));
        ReflectionTestUtils.setField(tokens, "jwtSecret", secret);
        ReflectionTestUtils.setField(tokens, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(tokens, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(tokens, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(tokens, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(tokens, "previousPublicKeyPem", "");
        tokens.init();
        return tokens;
    }

    @Test
    @DisplayName("an access token minted after a verified code carries the proof; a password-only one does not")
    void accessTokenStampedOnlyAfterTheChallenge() {
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST, true))).isTrue();
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST, false))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST))).isFalse();
    }

    @Test
    @DisplayName("both refresh-token forms carry the proof only when asked to")
    void refreshTokensCarryTheProof() {
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
            new CustomUserDetails(com.example.hms.model.User.builder().username("pharm.one").passwordHash("x").build(),
                Set.of(new SimpleGrantedAuthority("ROLE_PHARMACIST"))),
            null, List.of(new SimpleGrantedAuthority("ROLE_PHARMACIST")));

        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST, true))).isTrue();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST, false))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(authenticated, true))).isTrue();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(authenticated, false))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(authenticated))).isFalse();
    }

    @Test
    @DisplayName("an impersonation token carries the proof only after the super admin's TOTP step-up")
    void impersonationTokenCarriesTheStepUp() {
        UUID actor = UUID.randomUUID();

        assertThat(provider.hasSecondFactor(
            provider.generateImpersonationAccessToken(PHARMACIST, actor, "super.admin", 60_000L, true))).isTrue();
        assertThat(provider.hasSecondFactor(
            provider.generateImpersonationAccessToken(PHARMACIST, actor, "super.admin", 60_000L, false))).isFalse();
    }

    @Test
    @DisplayName("the MFA challenge token, a token from another issuer and garbage are never proof")
    void nothingElseIsProof() {
        assertThat(provider.hasSecondFactor(provider.generateMfaToken("pharm.one"))).isFalse();
        String foreign = provider("another-secret-entirely-another-secret-entirely-1234!!")
            .generateAccessToken(PHARMACIST, true);
        assertThat(provider.hasSecondFactor(foreign)).isFalse();
        assertThat(provider.hasSecondFactor("not.a.jwt")).isFalse();
        assertThat(provider.hasSecondFactor("")).isFalse();
        assertThat(provider.hasSecondFactor(null)).isFalse();
    }

    @Test
    @DisplayName("the amr reader: otp in an array or alone, nothing else")
    void amrReader() {
        assertThat(JwtTokenProvider.amrHoldsOtp(List.of("pwd", "otp"))).isTrue();
        assertThat(JwtTokenProvider.amrHoldsOtp("otp")).isTrue();
        assertThat(JwtTokenProvider.amrHoldsOtp(List.of("pwd"))).isFalse();
        assertThat(JwtTokenProvider.amrHoldsOtp(List.of("OTP"))).isFalse();
        assertThat(JwtTokenProvider.amrHoldsOtp("pwd otp")).isFalse();
        assertThat(JwtTokenProvider.amrHoldsOtp(null)).isFalse();
        assertThat(JwtTokenProvider.amrHoldsOtp(42)).isFalse();
    }
}
