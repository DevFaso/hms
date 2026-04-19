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
}
