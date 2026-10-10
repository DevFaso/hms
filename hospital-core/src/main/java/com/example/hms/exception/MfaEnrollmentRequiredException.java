package com.example.hms.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A provider user's request carried no second factor (provider plan AC-13).
 * Answered 403 with {@link #CODE}, so the portal sends the user to MFA
 * enrolment or the challenge instead of the forbidden page. Raised by the
 * provider confinement filter for every request outside the sign-in and MFA
 * enrolment handlers, whatever the handler (the answer is the same for a
 * reachable, a refused and an unmapped path, so it reveals nothing about which
 * exist), and by {@code POST /auth/mfa/enroll} when a provider user without
 * the factor would replace an existing authenticator.
 *
 * <p>An {@link AccessDeniedException}, so any code that treats a refusal as a
 * 403 keeps doing so. The message is localised by the thrower and carries no
 * identifier.
 */
public class MfaEnrollmentRequiredException extends AccessDeniedException {

    /** The error code in the 403 body, and the message key of its text. */
    public static final String CODE = "mfa.enrollment.required";

    public MfaEnrollmentRequiredException(String message) {
        super(message);
    }
}
