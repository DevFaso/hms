package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;

/**
 * An MFA step-up was refused: the action needs a current code from the
 * caller's authenticator ({@code X-Mfa-Token}) and did not get one it can
 * accept.
 *
 * <p>Answered 403 with the {@link Reason#code() reason code} in the body's
 * {@code code} field, never 401: the session itself is fine, so a client must
 * not treat the refusal as an expired session and sign the user out.
 */
public class MfaStepUpRefusedException extends RuntimeException {

    /** Why the step-up was refused; each code is also the message key. */
    public enum Reason {
        /** No code was sent. */
        REQUIRED("mfa.stepup.required"),
        /** The code is neither a current TOTP code nor an unused backup code. */
        INVALID("mfa.stepup.invalid"),
        /** The caller holds only the login's MFA challenge token, not a session. */
        CHALLENGE("mfa.stepup.challenge"),
        /** The actor has no authenticator and strict mode requires one. */
        NOT_ENROLLED("mfa.stepup.notEnrolled"),
        /** The actor's authenticator could not be read; refused rather than skipped. */
        UNAVAILABLE("mfa.stepup.unavailable"),
        /** No actor could be identified for the action, so there is no authenticator to ask. */
        UNIDENTIFIED("mfa.stepup.unidentified"),
        /** Too many wrong codes: the step-up is locked for a while. */
        LOCKED("mfa.stepup.locked");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;

    public MfaStepUpRefusedException(Reason reason) {
        super(MessageUtil.resolve(reason.code()));
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public String getCode() {
        return reason.code();
    }
}
