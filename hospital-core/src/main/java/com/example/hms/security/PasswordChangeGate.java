package com.example.hms.security;

import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.Set;

/**
 * An account on a temporary password (one someone else chose, with an
 * expiry) uses no API until it has set its own. A rotation of the holder's
 * own password is advisory and not gated here. {@link JwtAuthenticationFilter}
 * refuses every other request with a 403 whose {@code code} is {@link #CODE};
 * the portal sends the user to its account-setup screen on that code.
 *
 * <p>What such a session may still do: change the password (the change-password
 * call and the first sign-in setup), read who it is (the session bootstrap,
 * its own credential status, a patient's own profile), keep the session alive
 * (CSRF token, refresh) and sign out. The sign-in and reset endpoints are
 * public and listed so a stale bearer sent with them changes nothing.
 *
 * <p>An exact match on method and path after the context path, so anything
 * unusual (a trailing slash, an encoded character) is refused.
 */
@Slf4j
public final class PasswordChangeGate {

    /** The {@code code} of the refusal, which the portal and the apps act on. */
    public static final String CODE = "password_change_required";

    /** The bundle key of the refusal's message. */
    static final String MESSAGE_KEY = "auth.passwordChangeRequired";

    private static final Set<String> ADMITTED = Set.of(
        // Setting the password.
        "POST /auth/me/change-password",
        "POST /auth/me/complete-setup",
        // Reading who the caller is.
        "GET /auth/session/bootstrap",
        "GET /auth/credentials/me",
        "GET /me/patient/profile",
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

    private PasswordChangeGate() {
    }

    /** The admitted requests, as {@code "METHOD /path"} after the context path (PasswordChangeGateAllowlistTest). */
    static Set<String> admittedRequests() {
        return ADMITTED;
    }

    /**
     * Whether the caller is an account that must change its password first.
     * A super-admin impersonating such an account is not the holder, and is
     * not held to it.
     */
    public static boolean applies(Authentication authentication) {
        return authentication != null
            && authentication.getPrincipal() instanceof HospitalUserDetails details
            && details.isPasswordChangeRequired()
            && !ImpersonationContextHolder.isImpersonating();
    }

    /** Whether the request is one such an account may still make. */
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
            log.warn("[AUTH] Failed to write the password-change refusal body", ex);
        }
    }

    /** The message comes from a bundle: escape what JSON needs. */
    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
