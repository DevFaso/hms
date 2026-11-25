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

/**
 * Sessions end a fixed time after sign-in, however often they are refreshed:
 * 12 h for staff, 30 days for a session whose only role is ROLE_PATIENT.
 */
class SessionLifetimeTest {

    private static final Duration TWELVE_HOURS = Duration.ofHours(12);
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final Instant SIGNED_IN = Instant.parse("2026-11-24T06:00:00Z");
    private static final List<String> NURSE = List.of("ROLE_NURSE");
    private static final List<String> PATIENT = List.of("ROLE_PATIENT");
    private static final SessionLifetime LIFETIME = new SessionLifetime(TWELVE_HOURS, THIRTY_DAYS);

    /** The session judged at {@code now}. */
    private static java.util.Optional<SessionLifetime.Live> liveAt(Instant now, Instant signedInAt, Instant issuedAt,
                                                                    List<String> roles) {
        return SessionLifetimeTestSupport.at(now, TWELVE_HOURS, THIRTY_DAYS)
            .live(SessionLifetimeTestSupport.presented(signedInAt, issuedAt, roles));
    }

    @Test
    @DisplayName("a staff session is live until 12 h after sign-in, and not a moment after")
    void boundary() {
        Instant deadline = SIGNED_IN.plus(TWELVE_HOURS);
        Instant refreshedJustNow = deadline.minusSeconds(60);

        assertThat(liveAt(deadline.minusMillis(1), SIGNED_IN, refreshedJustNow, NURSE))
            .as("just under").get().extracting(SessionLifetime.Live::signedInAt).isEqualTo(SIGNED_IN);
        assertThat(liveAt(deadline.minusMillis(1), SIGNED_IN, refreshedJustNow, NURSE))
            .get().extracting(SessionLifetime.Live::endsAt).isEqualTo(deadline);
        assertThat(liveAt(deadline, SIGNED_IN, refreshedJustNow, NURSE)).as("at the deadline").isEmpty();
        assertThat(liveAt(deadline.plusSeconds(1), SIGNED_IN, refreshedJustNow, NURSE)).as("just over").isEmpty();
    }

    @Test
    @DisplayName("a patient-only session lasts 30 days; a patient who also holds a staff role keeps 12 h")
    void patientLifetime() {
        Instant twelveHoursOn = SIGNED_IN.plus(TWELVE_HOURS).plusSeconds(60);
        assertThat(liveAt(twelveHoursOn, SIGNED_IN, twelveHoursOn.minusSeconds(60), PATIENT))
            .as("patient only, 12 h + 1 min").get().extracting(SessionLifetime.Live::endsAt)
            .isEqualTo(SIGNED_IN.plus(THIRTY_DAYS));
        assertThat(liveAt(SIGNED_IN.plus(THIRTY_DAYS).minusMillis(1), SIGNED_IN, SIGNED_IN, PATIENT)).isPresent();
        assertThat(liveAt(SIGNED_IN.plus(THIRTY_DAYS), SIGNED_IN, SIGNED_IN, PATIENT)).as("patient past 30 d").isEmpty();
        assertThat(liveAt(twelveHoursOn, SIGNED_IN, SIGNED_IN, List.of("ROLE_PATIENT", "ROLE_NURSE")))
            .as("patient + staff, 12 h + 1 min").isEmpty();
        assertThat(liveAt(twelveHoursOn, SIGNED_IN, SIGNED_IN, List.of())).as("no role: staff lifetime").isEmpty();
        assertThat(LIFETIME.lifetimeFor(PATIENT)).isEqualTo(THIRTY_DAYS);
        assertThat(LIFETIME.lifetimeFor(List.of("PATIENT"))).as("only the canonical role").isEqualTo(TWELVE_HOURS);
    }

    @Test
    @DisplayName("a token without the sign-in time counts from its own issue time, never 'no limit'")
    void legacyTokenCountsFromItsIssueTime() {
        assertThat(liveAt(SIGNED_IN.plus(TWELVE_HOURS).minusSeconds(1), null, SIGNED_IN, NURSE))
            .get().extracting(SessionLifetime.Live::signedInAt).isEqualTo(SIGNED_IN);
        assertThat(liveAt(SIGNED_IN.plus(TWELVE_HOURS), null, SIGNED_IN, NURSE)).isEmpty();
        assertThat(liveAt(SIGNED_IN, null, null, NURSE)).as("no time at all: no session").isEmpty();
    }

    @Test
    @DisplayName("a sign-in time later than the token's issue time never extends the session")
    void aLaterSignInTimeNeverExtends() {
        Instant claimed = SIGNED_IN.plus(Duration.ofHours(6));
        assertThat(liveAt(SIGNED_IN.plus(TWELVE_HOURS), claimed, SIGNED_IN, NURSE)).isEmpty();
    }

    @Test
    @DisplayName("both lifetimes must be positive")
    void mustBePositive() {
        assertThatThrownBy(() -> new SessionLifetime(Duration.ZERO, THIRTY_DAYS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionLifetime(TWELVE_HOURS, Duration.ofHours(-1)))
            .isInstanceOf(IllegalArgumentException.class);
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
        SessionLifetime.Live live = LIFETIME.live(
            SessionLifetimeTestSupport.presented(original, provider.getIssuedAt(signIn).toInstant(), NURSE)).orElseThrow();
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
        SessionLifetime.Presented read = provider.presentedSession(legacy).orElseThrow();
        assertThat(LIFETIME.live(read)).as("issued 13 h ago: past the lifetime").isEmpty();
    }

    @Test
    @DisplayName("only a verified refresh token yields a presented session, carrying its own times and roles")
    void presentedSessionReadsOnlyARefreshToken() {
        JwtTokenProvider provider = provider();
        String refresh = provider.generateRefreshToken(
            new TokenUserDescriptor(UUID.randomUUID(), "patient.lifetime", PATIENT), null, "sid-p");
        Instant twelveHoursOn = Instant.now().plus(TWELVE_HOURS).plusSeconds(60);
        assertThat(SessionLifetimeTestSupport.at(twelveHoursOn, TWELVE_HOURS, THIRTY_DAYS)
            .live(provider.presentedSession(refresh).orElseThrow()))
            .as("the patient role set travels with the token").isPresent();

        String access = provider.generateAccessToken(nurse(), null, "sid-n");
        assertThat(provider.presentedSession(access)).as("an access token").isEmpty();
        // Another token's signature on this token's claims.
        String forged = refresh.substring(0, refresh.lastIndexOf('.')) + access.substring(access.lastIndexOf('.'));
        assertThat(provider.presentedSession(forged)).as("a tampered token").isEmpty();
        assertThat(provider.presentedSession("")).isEmpty();
    }
}
