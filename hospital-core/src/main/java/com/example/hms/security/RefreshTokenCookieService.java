package com.example.hms.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Helper for reading/writing the HttpOnly refresh-token cookie (S-01).
 *
 * <p>The refresh token is moved out of browser-accessible storage
 * ({@code localStorage} / {@code sessionStorage}) to mitigate XSS theft.
 * It is set by the backend on login and rotated on refresh, cleared on logout.
 *
 * <p>The companion access token remains in the JSON response body and is
 * held in client memory only (not persisted). The refresh cookie is scoped
 * to {@code /api/auth} so it is transmitted only to the refresh / logout
 * endpoints.
 */
@Component
public class RefreshTokenCookieService {

    /** Cookie name used to transport the refresh token. */
    public static final String COOKIE_NAME = "hms_refresh";

    /** Path scope: refresh cookie only attached to auth endpoints. */
    public static final String COOKIE_PATH = "/api/auth";

    private final boolean secure;
    private final String sameSite;

    public RefreshTokenCookieService(
            @Value("${app.auth.refresh-cookie.secure:true}") boolean secure,
            @Value("${app.auth.refresh-cookie.same-site:Strict}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /**
     * Writes the refresh token into an HttpOnly cookie on the response.
     *
     * @param response   the current HTTP response
     * @param token      the refresh JWT
     * @param maxAgeMs   lifetime in milliseconds (typically the token's remaining validity)
     */
    public void write(HttpServletResponse response, String token, long maxAgeMs) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(Duration.ofMillis(Math.max(0L, maxAgeMs)))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /** Reads the refresh token from the cookie, or {@code null} if absent. */
    public String read(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }

    /** Expires the refresh-token cookie on the client. */
    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
