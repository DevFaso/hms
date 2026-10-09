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
    boolean active,
    // The type of the assignment's facility (null for a global row), read from
    // the hospital already fetched with the assignment: the provider
    // confinement costs no query of its own (provider plan section 3.3).
    com.example.hms.enums.FacilityType facilityType
) {

    /** An assignment whose facility type is not known (a global row, or a hand-built view). */
    public TenantRoleAssignment(UUID hospitalId, UUID organizationId, String roleCode, String roleName,
                                boolean active) {
        this(hospitalId, organizationId, roleCode, roleName, active, null);
    }
}
