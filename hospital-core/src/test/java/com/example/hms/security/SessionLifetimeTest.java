package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Sessions end a fixed time (12 h by default) after sign-in, however often they are refreshed. */
class SessionLifetimeTest {

    private static final Duration TWELVE_HOURS = Duration.ofHours(12);
    private static final Instant SIGNED_IN = Instant.parse("2026-11-24T06:00:00Z");
    private static final SessionLifetime LIFETIME = new SessionLifetime(TWELVE_HOURS);

    @Test
    @DisplayName("a session is live until 12 h after sign-in, and not a moment after")
    void boundary() {
        Instant deadline = SIGNED_IN.plus(TWELVE_HOURS);
        Instant refreshedJustNow = deadline.minusSeconds(60);

        assertThat(LIFETIME.live(SIGNED_IN, refreshedJustNow, deadline.minusMillis(1)))
            .as("just under").get().extracting(SessionLifetime.Live::signedInAt).isEqualTo(SIGNED_IN);
        assertThat(LIFETIME.live(SIGNED_IN, refreshedJustNow, deadline)).as("at the deadline").isEmpty();
        assertThat(LIFETIME.live(SIGNED_IN, refreshedJustNow, deadline.plusSeconds(1))).as("just over").isEmpty();
    }

    @Test
    @DisplayName("a token without the sign-in time counts from its own issue time, never 'no limit'")
    void legacyTokenCountsFromItsIssueTime() {
        Instant issued = SIGNED_IN;
        assertThat(LIFETIME.live(null, issued, SIGNED_IN.plus(TWELVE_HOURS).minusSeconds(1)))
            .get().extracting(SessionLifetime.Live::signedInAt).isEqualTo(SIGNED_IN);
        assertThat(LIFETIME.live(null, issued, SIGNED_IN.plus(TWELVE_HOURS))).isEmpty();
        assertThat(LIFETIME.live(null, null, SIGNED_IN)).as("no time at all: no session").isEmpty();
    }

    @Test
    @DisplayName("a sign-in time later than the token's issue time never extends the session")
    void aLaterSignInTimeNeverExtends() {
        Instant claimed = SIGNED_IN.plus(Duration.ofHours(6));
        assertThat(LIFETIME.live(claimed, SIGNED_IN, SIGNED_IN.plus(TWELVE_HOURS))).isEmpty();
    }

    @Test
    @DisplayName("the lifetime must be positive")
    void mustBePositive() {
        assertThatThrownBy(() -> new SessionLifetime(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionLifetime(Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // ── The claim, through the real token provider ───────────────────────────

    private static final String SECRET = "session-lifetime-test-secret-minimum-256-bits-long-for-hmac!!";

    private static JwtTokenProvider provider() {
        JwtTokenProvider p = new JwtTokenProvider(mock(HospitalUserDetailsService.class),
            mock(TenantRoleAssignmentAccessor.class));
        ReflectionTestUtils.setField(p, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(p, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(p, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(p, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(p, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(p, "previousPublicKeyPem", "");
        p.init();
        return p;
    }

    private static TokenUserDescriptor nurse() {
        return new TokenUserDescriptor(UUID.randomUUID(), "nurse.lifetime", List.of("ROLE_NURSE"));
    }

    @Test
    @DisplayName("a sign-in stamps its own time; a refresh carries the original one on")
    void theClaimIsCarriedOn() {
        JwtTokenProvider provider = provider();
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String signIn = provider.generateRefreshToken(nurse(), null, "sid-1");
        Instant stamped = provider.signedInAt(signIn);
        assertThat(stamped).isBetween(before, Instant.now());
        assertThat(stamped).isEqualTo(provider.getIssuedAt(signIn).toInstant());

        Instant original = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.SECONDS);
        SessionLifetime.Live live = LIFETIME.live(original, provider.getIssuedAt(signIn).toInstant(), Instant.now()).orElseThrow();
        String rotated = provider.generateRefreshToken(nurse(), null, "sid-1", live);

        assertThat(provider.signedInAt(rotated)).isEqualTo(original);
        assertThat(provider.getIssuedAt(rotated).toInstant()).isAfter(original);
        assertThat(provider.getSessionId(rotated)).isEqualTo("sid-1");
        assertThat(provider.isRefreshToken(rotated)).isTrue();
    }

    @Test
    @DisplayName("a token minted before the claim existed reads as no sign-in time, so its issue time counts")
    void aLegacyTokenHasNoSignInTime() {
        JwtTokenProvider provider = provider();
        Instant issued = Instant.now().minus(Duration.ofHours(13)).truncatedTo(ChronoUnit.SECONDS);
        String legacy = Jwts.builder()
            .claim(TokenUse.CLAIM, TokenUse.REFRESH)
            .id(UUID.randomUUID().toString())
            .subject("nurse.lifetime")
            .claim("roles", List.of("ROLE_NURSE"))
            .claim(JwtTokenProvider.SESSION_ID_CLAIM, "sid-legacy")
            .issuedAt(Date.from(issued))
            .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

        assertThat(provider.isRefreshToken(legacy)).isTrue();
        assertThat(provider.signedInAt(legacy)).isNull();
        assertThat(LIFETIME.live(provider.signedInAt(legacy), provider.getIssuedAt(legacy).toInstant(), Instant.now()))
            .as("issued 13 h ago: past the lifetime").isEmpty();
    }
}
