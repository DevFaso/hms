package com.example.hms.security;

import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Set;

/**
 * A session whose screen is locked (the portal's idle lock) uses no API until
 * its holder has authenticated again: the password at
 * {@code POST /auth/verify-password}, or a new sign-in at the identity
 * provider (a token signed in there after the lock is not held by it;
 * {@code POST /auth/session/unlock} tells the portal so). The lock is kept on
 * the server ({@link TokenBlacklistService#lockSession}): per sign-in session
 * for a password session, per provider session for an identity-provider one
 * (with a copy per person, see {@link #providerPersonKey}). It does not
 * depend on what the browser remembers. Both bearer filters
 * refuse every other request with a 403 whose {@code code} is {@link #CODE};
 * the portal shows its lock screen on that code.
 *
 * <p>What a locked session may still do: lock again, unlock, read who it is
 * (the lock screen names the account), keep the session alive (CSRF token,
 * refresh) and sign out. The public sign-in and reset endpoints are listed so
 * a stale bearer sent with them changes nothing.
 *
 * <p>An exact match on method and path after the context path, so anything
 * unusual (a trailing slash, an encoded character) is refused.
 */
@Slf4j
public final class SessionLockGate {

    /** The {@code code} of the refusal, which the portal acts on. */
    public static final String CODE = "session_locked";

    /** The bundle key of the refusal's message. */
    public static final String MESSAGE_KEY = "auth.sessionLocked";

    /** The lock key of an identity-provider session: never a native session id (a bare UUID). */
    public static final String PROVIDER_SESSION_PREFIX = "oidc:sid:";

    /** The lock key of a person signed in through the identity provider. */
    public static final String PROVIDER_PERSON_PREFIX = "oidc:sub:";

    private static final Set<String> ADMITTED = Set.of(
        // Locking and unlocking.
        "POST /auth/session/lock",
        "POST /auth/session/unlock",
        "POST /auth/verify-password",
        // Reading who the caller is.
        "GET /auth/session/bootstrap",
        "GET /auth/credentials/me",
        // Keeping or ending the session.
        "GET /auth/csrf-token",
        "POST /auth/token/refresh",
        "POST /auth/logout",
        // Public sign-in and reset endpoints: the session plays no part in them.
        "POST /auth/login",
        "POST /auth/mfa/verify",
        "POST /auth/password/request",
        "POST /auth/password/confirm",
        "POST /auth/request-reset",
        "POST /auth/reset-password");

    private SessionLockGate() {
    }

    /** The admitted requests, as {@code "METHOD /path"} after the context path (SessionLockGateTest). */
    static Set<String> admittedRequests() {
        return ADMITTED;
    }

    /**
     * The key that holds an identity-provider token: its provider session
     * ({@code sid}), so a lock in one browser does not hold the person's
     * other sessions; the person when the token names no session;
     * {@code null} when it names neither.
     */
    public static String providerLockKey(org.springframework.security.oauth2.jwt.Jwt token) {
        if (token == null) {
            return null;
        }
        String sid = token.getClaimAsString("sid");
        return sid == null || sid.isBlank() ? providerPersonKey(token) : PROVIDER_SESSION_PREFIX + sid;
    }

    /**
     * The copy of a provider lock kept per person, or {@code null} when the
     * token names nobody. A new sign-in at the provider may open a new
     * provider session; the unlock then proves the new sign-in against the
     * person's latest lock ({@code POST /auth/session/unlock}).
     */
    public static String providerPersonKey(org.springframework.security.oauth2.jwt.Jwt token) {
        String subject = token == null ? null : token.getSubject();
        return subject == null || subject.isBlank() ? null : PROVIDER_PERSON_PREFIX + subject;
    }

    /**
     * Whether an identity-provider token is held by a lock set at
     * {@code lockedAtMs}: it is, unless it carries a sign-in time at the
     * provider that is not earlier than the lock (whole seconds there: a
     * sign-in in the second of the lock counts). No lock time, an unreadable
     * one or no sign-in time: locked.
     */
    public static boolean providerLocked(Long lockedAtMs, org.springframework.security.oauth2.jwt.Jwt token) {
        if (lockedAtMs == null || lockedAtMs <= 0 || token == null) {
            return true;
        }
        java.time.Instant signedInAt;
        try {
            signedInAt = token.getClaimAsInstant("auth_time");
        } catch (RuntimeException unreadable) {
            signedInAt = null;
        }
        return signedInAt == null || signedInAt.toEpochMilli() + 999 < lockedAtMs;
    }

    /** Whether the request is one a locked session may still make. */
    public static boolean admits(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if (uri == null || method == null) {
            return false;
        }
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        if (!uri.startsWith(contextPath)) {
            return false;
        }
        return ADMITTED.contains(method + " " + uri.substring(contextPath.length()));
    }

    /** 403 with the code and a message in the caller's language. Names no account. */
    public static void writeRefusal(HttpServletResponse response) {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                + "\"code\":\"" + CODE + "\","
                + "\"message\":\"" + jsonEscape(MessageUtil.resolve(MESSAGE_KEY)) + "\"}");
        } catch (IOException ex) {
            log.warn("[AUTH] Failed to write the session-lock refusal body", ex);
        }
    }

    /** The message comes from a bundle: escape what JSON needs. */
    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
