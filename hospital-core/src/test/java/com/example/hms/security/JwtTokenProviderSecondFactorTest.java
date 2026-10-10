package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The legacy issuer's second-factor proof (provider plan AC-13): only the
 * tokens minted after a verified TOTP code carry {@code amr: ["pwd", "otp"]}
 * and the time it was verified, a refresh token passes both on, the proof
 * lapses after the 48 h max age (Keycloak's Authenticator Reference max age),
 * and nothing else (a password-only token, the MFA challenge token, a tampered
 * or foreign token) reads as proof.
 */
class JwtTokenProviderSecondFactorTest {

    private static final long MAX_AGE = 172_800L;
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

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("an access token minted after a verified code carries the proof and its time; a password-only one does not")
    void accessTokenStampedOnlyAfterTheChallenge() {
        Instant verified = now();

        assertThat(provider.secondFactorAt(provider.generateAccessToken(PHARMACIST, verified))).isEqualTo(verified);
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST, (Instant) null))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST))).isFalse();
    }

    @Test
    @DisplayName("both refresh-token forms carry the proof and the ORIGINAL time only when asked to")
    void refreshTokensCarryTheProof() {
        Instant verified = now().minusSeconds(3_600);
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
            new CustomUserDetails(com.example.hms.model.User.builder().username("pharm.one").passwordHash("x").build(),
                Set.of(new SimpleGrantedAuthority("ROLE_PHARMACIST"))),
            null, List.of(new SimpleGrantedAuthority("ROLE_PHARMACIST")));

        assertThat(provider.secondFactorAt(provider.generateRefreshToken(PHARMACIST, verified))).isEqualTo(verified);
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST, (Instant) null))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST))).isFalse();
        assertThat(provider.secondFactorAt(provider.generateRefreshToken(authenticated, verified))).isEqualTo(verified);
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(authenticated, (Instant) null))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(authenticated))).isFalse();
    }

    @Test
    @DisplayName("the proof lapses after the max age: an old token stops counting, and no token is minted with an old time")
    void proofLapsesAfterTheMaxAge() {
        Instant almostStale = now().minusSeconds(MAX_AGE - 120);
        String refresh = provider.generateRefreshToken(PHARMACIST, almostStale);
        assertThat(provider.hasSecondFactor(refresh)).isTrue();

        // As if four minutes passed: the same token is no proof any more.
        ReflectionTestUtils.setField(provider, "secondFactorMaxAgeSeconds", MAX_AGE - 240);
        assertThat(provider.hasSecondFactor(refresh)).isFalse();
        assertThat(provider.secondFactorAt(refresh)).isNull();

        // A token minted with a time past the max age is no proof, so a refresh chain cannot renew it.
        ReflectionTestUtils.setField(provider, "secondFactorMaxAgeSeconds", MAX_AGE);
        Instant stale = now().minusSeconds(MAX_AGE + 60);
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST, stale))).isFalse();
        assertThat(provider.hasSecondFactor(provider.generateRefreshToken(PHARMACIST, stale))).isFalse();
        // A time in the future is no proof either.
        assertThat(provider.hasSecondFactor(provider.generateAccessToken(PHARMACIST, now().plusSeconds(3_600))))
            .isFalse();
    }

    @Test
    @DisplayName("an impersonation token carries the proof only after the super admin's TOTP step-up")
    void impersonationTokenCarriesTheStepUp() {
        UUID actor = UUID.randomUUID();

        assertThat(provider.hasSecondFactor(
            provider.generateImpersonationAccessToken(PHARMACIST, actor, "super.admin", 60_000L, now()))).isTrue();
        assertThat(provider.hasSecondFactor(
            provider.generateImpersonationAccessToken(PHARMACIST, actor, "super.admin", 60_000L, null))).isFalse();
    }

    @Test
    @DisplayName("the MFA challenge token, a token from another issuer and garbage are never proof")
    void nothingElseIsProof() {
        assertThat(provider.hasSecondFactor(provider.generateMfaToken("pharm.one"))).isFalse();
        String foreign = provider("another-secret-entirely-another-secret-entirely-1234!!")
            .generateAccessToken(PHARMACIST, now());
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
