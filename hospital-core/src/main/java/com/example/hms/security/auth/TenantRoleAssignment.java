package com.example.hms.security.auth;

import java.util.UUID;

/**
 * Lightweight projection representing a user's role assignment with the tenant identifiers required for JWT scope.
 */
public record TenantRoleAssignment(
    UUID hospitalId,
    UUID organizationId,
    String roleCode,
    String roleName,
    boolean active
) {
}
