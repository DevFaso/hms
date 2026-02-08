package com.example.hms.security;

import java.util.Collection;
import java.util.UUID;

/**
 * Lightweight payload supplied when issuing tokens outside the authentication flow.
 * Roles are optional; when omitted the token provider will resolve them via tenant assignments.
 */
public record TokenUserDescriptor(
    UUID userId,
    String username,
    Collection<String> roles
) {
}
