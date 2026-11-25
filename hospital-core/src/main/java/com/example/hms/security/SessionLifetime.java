package com.example.hms.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The absolute lifetime of a sign-in session: however often it is refreshed,
 * a session ends a fixed time after its sign-in, and the next sign-in asks for
 * the password (and the code) again. Staff sessions end after
 * {@link #maxLifetime()} (12 h by default); a session whose only role is
 * {@value #PATIENT_ROLE} after {@link #patientMaxLifetime()} (30 days).
 *
 * <p>A refresh token carries the time its session was signed in to
 * ({@link JwtTokenProvider#SIGNED_IN_AT_CLAIM}), passed on unchanged by every
 * rotation. A token minted before the claim existed counts from its own
 * issue time: the session then ends at most one lifetime after that token,
 * never "no limit".
 *
 * <p>The clock cannot be restarted by construction: {@link #live} takes only
 * a {@link Presented} session, which {@link JwtTokenProvider} reads from a
 * verified refresh token (nothing else can build one), and reads the time
 * itself. Continuing a session needs the {@link Live} it returns (the
 * four-argument refresh-token mint of {@link JwtTokenProvider} takes one).
 */
public final class SessionLifetime {

    /** The default for staff: sessions end 12 h after sign-in. */
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofHours(12);

    /** The default for a patient-only session: 30 days after sign-in. */
    public static final Duration DEFAULT_PATIENT_MAX_LIFETIME = Duration.ofDays(30);

    /** The one role whose sessions get the patient lifetime, when it is the session's only role. */
    public static final String PATIENT_ROLE = "ROLE_PATIENT";

    private final Duration maxLifetime;
    private final Duration patientMaxLifetime;
    private final Clock clock;

    public SessionLifetime(Duration maxLifetime, Duration patientMaxLifetime) {
        this(maxLifetime, patientMaxLifetime, Clock.systemUTC());
    }

    /** Tests pin the clock; production reads the system's. */
    SessionLifetime(Duration maxLifetime, Duration patientMaxLifetime, Clock clock) {
        this.maxLifetime = positive(maxLifetime);
        this.patientMaxLifetime = positive(patientMaxLifetime);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static Duration positive(Duration lifetime) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isNegative() || lifetime.isZero()) {
            // A start-up configuration fault (AuthControllerProperties names the property), never a client answer.
            throw new IllegalArgumentException("lifetime");
        }
        return lifetime;
    }

    /** The staff session lifetime. */
    public Duration maxLifetime() {
        return maxLifetime;
    }

    /** The lifetime of a session whose only role is {@value #PATIENT_ROLE}. */
    public Duration patientMaxLifetime() {
        return patientMaxLifetime;
    }

    /**
     * The lifetime of a session holding exactly {@code roles}: the patient one
     * only when the set is exactly {@value #PATIENT_ROLE}; any other set, a
     * staff role alongside included, keeps the staff one.
     */
    public Duration lifetimeFor(Collection<String> roles) {
        return roles != null && roles.size() == 1 && PATIENT_ROLE.equals(roles.iterator().next())
            ? patientMaxLifetime
            : maxLifetime;
    }

    /** When a session signed in to at {@code signedInAt} with {@code roles} ends. */
    public Instant endsAt(Instant signedInAt, Collection<String> roles) {
        return signedInAt.plus(lifetimeFor(roles));
    }

    /**
     * The session a presented refresh token continues, when it is still within
     * its lifetime now; empty once now has reached its end.
     */
    public Optional<Live> live(Presented session) {
        Objects.requireNonNull(session, "session");
        Instant start = sessionStart(session.signedInAt, session.issuedAt);
        if (start == null) {
            return Optional.empty();
        }
        Instant ends = endsAt(start, session.roles);
        return clock.instant().isBefore(ends) ? Optional.of(new Live(start, ends)) : Optional.empty();
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
     * What a verified refresh token says about its session: when it was signed
     * in to ({@code null} for a token minted before the claim), the token's
     * own issue time, and the role set it carries. Built only by
     * {@link JwtTokenProvider#presentedSession} from a token whose signature
     * and expiry it has checked.
     */
    public static final class Presented {
        private final Instant signedInAt;
        private final Instant issuedAt;
        private final List<String> roles;

        Presented(Instant signedInAt, Instant issuedAt, List<String> roles) {
            this.signedInAt = signedInAt;
            this.issuedAt = issuedAt;
            this.roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * A session found within its lifetime by {@link SessionLifetime#live}: the
     * only way to mint the refresh token that continues it. Not constructible
     * elsewhere.
     */
    public static final class Live {
        private final Instant signedInAt;
        private final Instant endsAt;

        private Live(Instant signedInAt, Instant endsAt) {
            this.signedInAt = signedInAt;
            this.endsAt = endsAt;
        }

        /** When the session was signed in to: the rotated token carries it on. */
        public Instant signedInAt() {
            return signedInAt;
        }

        /** When the session ends: no refresh after it. */
        public Instant endsAt() {
            return endsAt;
        }
    }
}
