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

    /**
     * A row at a facility must say which kind of facility it is: an unknown
     * type would read as "not a provider" and leave the caller unconfined
     * (provider plan section 3.3), so it is refused here instead.
     */
    public TenantRoleAssignment {
        if (hospitalId != null && facilityType == null) {
            throw new IllegalArgumentException("An assignment at a facility needs its facility type");
        }
    }
}
