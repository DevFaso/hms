package com.example.hms.security;

/**
 * The claim that says what an HMS-minted token is for. The same key signs the
 * access, refresh, impersonation and MFA challenge tokens, so the signature
 * alone cannot tell a refresh token from the others: {@code /auth/token/refresh}
 * accepts only a token marked {@link #REFRESH}, and nothing else carries that
 * mark.
 */
public final class TokenUse {

    public static final String CLAIM = "token_use";
    public static final String REFRESH = "refresh";

    private TokenUse() {
    }
}
