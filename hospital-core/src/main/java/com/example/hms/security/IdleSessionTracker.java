package com.example.hms.security;

import java.util.UUID;

/**
 * Tracks the most-recent authenticated activity per user so the security
 * filters can reject requests after a configurable idle window. Implementations
 * are expected to be O(1) per call (single Redis GET / SET) because every
 * authenticated request hits {@link #isIdle(UUID)} and {@link #touch(UUID)}.
 *
 * <p>Sister to {@link TokenBlacklistService}: same Redis-or-in-memory
 * conditional-bean pattern, same Javadoc shape, same fail-open posture.
 *
 * <p>Keys carry only the opaque user UUID — never username, email, or any
 * patient identifier. Values are the touch timestamp in epoch milliseconds.
 *
 * <p>Added in v1.0 / Security / Idle session timeout (roadmap row 7).
 */
public interface IdleSessionTracker {

    /**
     * Whether idle tracking is wired in this deployment. The
     * {@link com.example.hms.security.IdleSessionGate} short-circuits when
     * this returns {@code false} so the cost of the feature in environments
     * that have it disabled (local-h2 dev, integration tests) is one
     * boolean check per request.
     */
    boolean isEnabled();

    /**
     * Mark the user as having had recent authenticated activity. Resets the
     * TTL on the underlying entry so the next {@link #isIdle(UUID)} call
     * returns {@code false} for the configured idle window.
     *
     * <p>No-op when {@code userId} is null. The tracker is fail-open: a
     * persistence-layer outage logs a throttled WARN and treats the touch
     * as successful so an unrelated Redis blip cannot lock every clinician
     * out of a hospital.
     */
    void touch(UUID userId);

    /**
     * @return {@code true} when the user has had no activity within the
     *     configured idle window (i.e. their entry has been TTL-evicted
     *     or was never set in this session). Returns {@code false} when
     *     {@code userId} is null, the tracker is disabled, or — under
     *     fail-open — the persistence layer is unavailable.
     */
    boolean isIdle(UUID userId);

    /**
     * Drop the user's entry. Called on logout for tidiness; not required
     * for correctness because the entry would be TTL-evicted anyway.
     * No-op when {@code userId} is null.
     */
    void clear(UUID userId);
}
