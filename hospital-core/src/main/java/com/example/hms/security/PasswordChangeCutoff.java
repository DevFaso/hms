package com.example.hms.security;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * QA D114: a token minted before the account's last password change is no
 * longer a session. A password change is what a user does when they think
 * someone else has their credentials, so every access and refresh token
 * issued before it must stop working, not run on to its expiry.
 *
 * <p>A JWT {@code iat} has whole-second precision, so the change instant is
 * truncated to the second and compared strictly: a token minted in the same
 * second as the change survives. {@code passwordChangedAt} is written with
 * {@code LocalDateTime.now()} in the system zone, and is read back in it.
 */
public final class PasswordChangeCutoff {

    private PasswordChangeCutoff() {
    }

    /** Whether a token issued at {@code issuedAt} predates the password change. */
    public static boolean predates(Date issuedAt, LocalDateTime passwordChangedAt) {
        if (issuedAt == null || passwordChangedAt == null) {
            return false;
        }
        return issuedAt.toInstant().isBefore(
            passwordChangedAt.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()).toInstant());
    }
}
