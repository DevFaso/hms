package com.example.hms.security;

import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

/**
 * Contract for user principals that expose the canonical user identifier used across services.
 */
public interface HospitalUserDetails extends UserDetails {

    /**
     * Returns the UUID of the associated user record.
     */
    UUID getUserId();

    /** When the account's password last changed, or null if unknown (QA D114). */
    default java.time.LocalDateTime getPasswordChangedAt() {
        return null;
    }

    /**
     * Whether the account must set its own password before any other use: a
     * temporary password, or a rotation the holder has not done yet. Enforced
     * on every request by {@link PasswordChangeGate}.
     */
    default boolean isPasswordChangeRequired() {
        return false;
    }
}
