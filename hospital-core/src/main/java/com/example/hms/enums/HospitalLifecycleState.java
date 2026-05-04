package com.example.hms.enums;

/**
 * Lifecycle states for an individual Hospital (MVP-c batch).
 *
 * <p>Mirrors {@link OrganizationLifecycleState} so a super admin can
 * suspend / archive / restore / schedule-purge a single facility
 * independently of its parent organization. Suspending an organization
 * implicitly blocks login at every hospital under it (handled in
 * {@code JwtAuthenticationFilter}); restoring the parent does NOT
 * auto-resume hospitals that were suspended independently — those
 * must be restored explicitly so a partial off-boarding stays
 * partial.
 *
 * <pre>
 *   ACTIVE         → SUSPENDED       (suspend)
 *   ACTIVE         → ARCHIVED        (archive)
 *   SUSPENDED      → ACTIVE          (restore)
 *   SUSPENDED      → ARCHIVED        (archive)
 *   ARCHIVED       → ACTIVE          (restore)
 *   ARCHIVED       → PENDING_PURGE   (schedule-purge)
 *   PENDING_PURGE  → ARCHIVED        (cancel-purge)
 *   PENDING_PURGE  → PURGED          (executed by HospitalPurgeJob)
 * </pre>
 *
 * {@code PURGED} is terminal.
 */
public enum HospitalLifecycleState {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
    PENDING_PURGE,
    PURGED
}
