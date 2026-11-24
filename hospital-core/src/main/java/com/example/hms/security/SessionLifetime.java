package com.example.hms.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The absolute lifetime of a sign-in session: however often it is refreshed,
 * a session ends {@link #maxLifetime()} after its sign-in, and the next
 * sign-in asks for the password (and the code) again.
 *
 * <p>A refresh token carries the time its session was signed in to
 * ({@link JwtTokenProvider#SIGNED_IN_AT_CLAIM}), passed on unchanged by every
 * rotation. A token minted before the claim existed counts from its own
 * issue time: the session then ends at most {@link #maxLifetime()} after that
 * token, never "no limit".
 *
 * <p>Continuing a session needs a {@link Live}, and only {@link #live} makes
 * one, so a refresh cannot mint a pair without this check (the four-argument
 * refresh-token mint of {@link JwtTokenProvider} takes a {@code Live}).
 */
public record SessionLifetime(Duration maxLifetime) {

    /** The default: sessions end 12 h after sign-in. */
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofHours(12);

    public SessionLifetime {
        Objects.requireNonNull(maxLifetime, "maxLifetime is required");
        if (maxLifetime.isNegative() || maxLifetime.isZero()) {
            // A start-up configuration fault (AuthControllerProperties explains it), never a client answer.
            throw new IllegalArgumentException("app.auth.session-max-lifetime");
        }
    }

    /**
     * The session a refresh token continues, when it is still within its
     * lifetime at {@code now}; empty once {@code now} has reached
     * sign-in + {@link #maxLifetime()}.
     *
     * @param signedInAt the token's {@link JwtTokenProvider#SIGNED_IN_AT_CLAIM},
     *                   {@code null} for a token minted before it existed
     * @param issuedAt   the token's {@code iat}
     */
    public Optional<Live> live(Instant signedInAt, Instant issuedAt, Instant now) {
        Objects.requireNonNull(now, "now is required");
        Instant start = sessionStart(signedInAt, issuedAt);
        if (start == null || !now.isBefore(start.plus(maxLifetime))) {
            return Optional.empty();
        }
        return Optional.of(new Live(start));
    }

    /**
     * The earlier of the two: a sign-in time later than the token's own issue
     * time is not one this issuer wrote, and never extends the session. With
     * neither, there is no start to count from: the session does not go on.
     */
    private static Instant sessionStart(Instant signedInAt, Instant issuedAt) {
        if (signedInAt == null) {
            return issuedAt;
        }
        return issuedAt == null || signedInAt.isBefore(issuedAt) ? signedInAt : issuedAt;
    }

    /**
     * A session found within its lifetime by {@link SessionLifetime#live}: the
     * only way to mint the refresh token that continues it. Not constructible
     * elsewhere.
     */
    public static final class Live {
        private final Instant signedInAt;

        private Live(Instant signedInAt) {
            this.signedInAt = signedInAt;
        }

        /** When the session was signed in to: the rotated token carries it on. */
        public Instant signedInAt() {
            return signedInAt;
        }
    }
}
