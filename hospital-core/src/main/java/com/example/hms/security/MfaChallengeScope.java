package com.example.hms.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Set;

/**
 * What the MFA challenge token may do.
 *
 * <p>{@code POST /auth/login} answers a user who must pass MFA with a
 * five-minute challenge token instead of a session. It proves the password and
 * nothing more, so it is not a session: {@code /auth/mfa/verify} reads it from
 * the request body, and as a bearer it authenticates on the two requests that
 * set up a FIRST authenticator during login (the portal's {@code /mfa-enroll}
 * page) and nowhere else. On every other request {@link JwtAuthenticationFilter}
 * leaves the caller anonymous, exactly as if no token had been sent, and
 * {@code /auth/token/refresh} refuses it.
 *
 * <p>Even on those two requests it cannot replace an authenticator that is
 * already verified: {@code MfaService#requireStepUpToReplaceAuthenticator}
 * refuses a challenge session outright.
 */
public final class MfaChallengeScope {

    /** The claim that marks the challenge token; no other token this issuer mints carries it. */
    public static final String PURPOSE_CLAIM = "purpose";
    public static final String PURPOSE = "mfa_challenge";

    /**
     * The only authority a challenge session holds. Not a {@code ROLE_}, so no
     * role gate admits it; it exists so the MFA handlers can tell a challenge
     * session from a full one.
     */
    public static final String AUTHORITY = "MFA_CHALLENGE";

    /** First-time enrolment during login: start it, then confirm the first code. */
    private static final Set<String> ENROLMENT_PATHS = Set.of(
        "/auth/mfa/enroll",
        "/auth/mfa/verify-enrollment");

    private MfaChallengeScope() {
    }

    /** The caller authenticated with the challenge token, not a full session. */
    public static boolean isChallengeSession(Authentication authentication) {
        return authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(granted -> AUTHORITY.equals(granted.getAuthority()));
    }

    /**
     * The request is one the challenge token may authenticate. An exact match
     * on the raw URI, so anything unusual (a trailing slash, an encoded
     * character) fails closed: the caller is anonymous.
     */
    public static boolean admits(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return uri != null
            && uri.startsWith(contextPath)
            && ENROLMENT_PATHS.contains(uri.substring(contextPath.length()));
    }
}
