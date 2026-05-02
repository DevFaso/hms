package com.example.hms.service;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of which organizations are in a non-active lifecycle state
 * (SUSPENDED / ARCHIVED / PENDING_PURGE / PURGED).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code JwtAuthenticationFilter} — to reject tokens whose user is
 *       attached to a blocked organization (super admin bypasses the check).
 *   <li>{@code TenantScopeSpecification} — to drop blocked org IDs from
 *       permitted scope before building the query predicate.
 * </ul>
 *
 * <p>Implementations are expected to cache for a short TTL so per-request
 * invocations do not hit the DB. {@link #invalidate()} is called on every
 * lifecycle transition to keep the cache fresh.
 */
public interface OrganizationLifecycleStatusService {

    /** Snapshot of all organization IDs currently in a non-active state. */
    Set<UUID> getBlockedOrganizationIds();

    /** True iff the given organization ID is in a blocked state. */
    default boolean isBlocked(UUID organizationId) {
        return organizationId != null && getBlockedOrganizationIds().contains(organizationId);
    }

    /** Drop the cached snapshot — call after a lifecycle transition. */
    void invalidate();
}
