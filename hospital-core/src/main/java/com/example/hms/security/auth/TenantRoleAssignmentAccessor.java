package com.example.hms.security.auth;

import java.util.List;
import java.util.UUID;

/**
 * Abstraction over the persistence layer used by JWT components to resolve role assignments for a user.
 */
public interface TenantRoleAssignmentAccessor {

    /**
     * Returns every role assignment for the provided user, including inactive rows when present.
     */
    List<TenantRoleAssignment> findAssignmentsForUser(UUID userId);
}
