package com.example.hms.enums;

/**
 * Tenant lifecycle states for an Organization (MVP-2).
 *
 * <p>Transitions are enforced by {@code OrganizationLifecycleService}:
 * <pre>
 *   ACTIVE         → SUSPENDED       (suspend)
 *   ACTIVE         → ARCHIVED        (archive)
 *   SUSPENDED      → ACTIVE          (restore)
 *   SUSPENDED      → ARCHIVED        (archive)
 *   ARCHIVED       → ACTIVE          (restore)
 *   ARCHIVED       → PENDING_PURGE   (schedule-purge)
 *   PENDING_PURGE  → ARCHIVED        (cancel-purge)
 *   PENDING_PURGE  → PURGED          (executed by TenantPurgeJob)
 * </pre>
 * {@code PURGED} is terminal — no further transitions are permitted.
 */
public enum OrganizationLifecycleState {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
    PENDING_PURGE,
    PURGED
}
