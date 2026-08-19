package com.example.hms.service;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of which hospitals are in a non-active lifecycle state
 * (SUSPENDED / ARCHIVED / PENDING_PURGE / PURGED) — MVP-c batch.
 *
 * <p>Mirrors {@link OrganizationLifecycleStatusService} for the
 * hospital level. Used by {@code JwtAuthenticationFilter} to reject
 * tokens whose user has an active assignment at a blocked hospital
 * (super admin bypasses the check).
 *
 * <p>Implementations cache for a short TTL so per-request invocations
 * do not hit the DB. {@link #invalidate()} is called on every hospital
 * lifecycle transition.
 */
public interface HospitalLifecycleStatusService {

    Set<UUID> getBlockedHospitalIds();

    default boolean isBlocked(UUID hospitalId) {
        return hospitalId != null && getBlockedHospitalIds().contains(hospitalId);
    }

    void invalidate();
}
