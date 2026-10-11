package com.example.hms.security;

/**
 * Service for blacklisting revoked JWT token IDs (jti).
 * Tokens are stored until their natural expiration, after which they
 * are automatically evicted since they would fail signature validation anyway.
 */
public interface TokenBlacklistService {

    /**
     * Blacklist a token by its jti claim.
     *
     * @param jti           the JWT ID
     * @param expirationMs  the token's expiration timestamp in epoch millis;
     *                      used to auto-evict the entry once the token would
     *                      have expired naturally
     */
    void blacklist(String jti, long expirationMs);

    /**
     * Check whether a token has been blacklisted.
     */
    boolean isBlacklisted(String jti);

    /**
     * Spend a single-use token: blacklist {@code jti} only if it is not
     * already, atomically. {@code true} for the one caller that spent it,
     * {@code false} for every other (and for a blank jti or an expired token).
     * The Redis store is atomic across instances; the in-memory one only
     * within its JVM, so a multi-instance deployment needs Redis.
     */
    boolean claim(String jti, long expirationMs);

    /**
     * Records that the refresh token {@code jti} was just rotated, for
     * {@code graceMs}. Another tab of the same browser that refreshed with the
     * same cookie a moment later is then told "superseded, retry" (409)
     * instead of "replayed, sign in again" (401), which would sign out every
     * tab: the browser already holds the rotated cookie.
     */
    void markRotated(String jti, long graceMs);

    /** {@code jti} was rotated within its grace period ({@link #markRotated}). */
    boolean wasRecentlyRotated(String jti);
}
