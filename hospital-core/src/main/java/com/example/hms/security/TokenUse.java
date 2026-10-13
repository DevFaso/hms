package com.example.hms.security;

import java.util.Map;
import java.util.Set;

/**
 * The claim that says what an HMS-minted token is for. The same key signs the
 * access, refresh, impersonation and MFA challenge tokens, so the signature
 * alone cannot tell a refresh token from the others: {@code /auth/token/refresh}
 * accepts only a token marked {@link #REFRESH}, nothing else carries that mark,
 * and a refresh token is never accepted as a bearer access token.
 */
public final class TokenUse {

    public static final String CLAIM = "token_use";
    public static final String REFRESH = "refresh";

    /**
     * Every claim a refresh token minted before the mark existed carries, and
     * nothing more. An access token always adds {@code uid} and the tenant
     * claims, an impersonation token the impersonator, the MFA challenge token
     * {@code purpose}: none of them has this shape.
     */
    private static final Set<String> LEGACY_REFRESH_CLAIMS = Set.of("jti", "sub", "roles", "iat", "exp");

    private TokenUse() {
    }

    /** Marked {@code token_use=refresh}. */
    public static boolean isMarkedRefresh(Map<String, ?> claims) {
        return REFRESH.equals(claims.get(CLAIM));
    }

    /**
     * The shape of a refresh token minted before the mark: a subject and roles,
     * and no claim beyond {@link #LEGACY_REFRESH_CLAIMS}.
     */
    public static boolean isLegacyRefreshShape(Map<String, ?> claims) {
        return claims.containsKey("sub") && claims.containsKey("roles")
            && LEGACY_REFRESH_CLAIMS.containsAll(claims.keySet());
    }

    /** A refresh token of either generation: never accepted as a bearer access token. */
    public static boolean isRefreshShaped(Map<String, ?> claims) {
        return isMarkedRefresh(claims) || isLegacyRefreshShape(claims);
    }
}
