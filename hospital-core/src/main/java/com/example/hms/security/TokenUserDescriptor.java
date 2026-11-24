package com.example.hms.security;

import java.util.Collection;
import java.util.UUID;

/**
 * The user and the roles a token is minted with.
 * The roles are exactly the ones minted: an empty set mints a token with no role.
 */
public record TokenUserDescriptor(
    UUID userId,
    String username,
    Collection<String> roles
) {
}
