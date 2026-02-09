package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Slf4j
public final class JwtTokenHolder {

    private JwtTokenHolder() {
    }

    /**
     * @deprecated since 1.0, for removal. Tokens now ride along with the Authentication credentials.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public static void setToken(String token) {
        log.trace("Ignoring legacy JwtTokenHolder#setToken call. tokenLength={}", token == null ? 0 : token.length());
    }

    public static String getToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object credentials = authentication.getCredentials();
        if (credentials instanceof String token && !token.isBlank()) {
            return token;
        }

        if (credentials != null) {
            String value = credentials.toString();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    /**
     * @deprecated since 1.0, for removal. Tokens now ride along with the Authentication credentials.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public static void clear() {
        // No-op: token is held by the security context now
    }

}
