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
}
