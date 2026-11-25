package com.example.hms.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Test-only access to what production code cannot build: a presented session
 * with chosen times (production reads one only from a verified token) and a
 * live session signed in to at a chosen time (to mint an old-session token).
 */
public final class SessionLifetimeTestSupport {

    private SessionLifetimeTestSupport() {
    }

    /** A refresh token's session as {@link JwtTokenProvider#presentedSession} would read it. */
    public static SessionLifetime.Presented presented(Instant signedInAt, Instant issuedAt, List<String> roles) {
        return new SessionLifetime.Presented(signedInAt, issuedAt, roles);
    }

    /** A lifetime judged against a fixed clock. */
    public static SessionLifetime at(Instant now, Duration staff, Duration patient) {
        return new SessionLifetime(staff, patient, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A live session signed in to at {@code signedInAt}, whatever its age now. */
    public static SessionLifetime.Live signedInAt(Instant signedInAt) {
        return at(signedInAt, Duration.ofDays(1), Duration.ofDays(1))
            .live(presented(signedInAt, signedInAt, List.of()))
            .orElseThrow();
    }
}
