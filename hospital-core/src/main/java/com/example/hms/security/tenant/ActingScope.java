package com.example.hms.security.tenant;

import java.util.UUID;

/**
 * The answer to "which hospital is this request acting at, or is it a
 * super-admin in global view?" — one value per request, computed once from the
 * live assignment table on both auth paths (docs/security/tenant-resolution.md
 * §3.1).
 *
 * <p>{@code null} never carries meaning: a caller matches on the three cases.
 * {@link Global} exists only for a verified super-admin (a live active
 * SUPER_ADMIN assignment), so a caller that needs a hospital asks
 * {@link ActingScopeResolver#requirePinned()} and cannot receive global view
 * by accident.
 */
public sealed interface ActingScope {

    /** Acting at one hospital. */
    record Pinned(UUID hospitalId, Source source) implements ActingScope { }

    /** A verified super-admin reading across every hospital. Read-only. */
    record Global(UUID superAdminUserId) implements ActingScope { }

    /** No hospital can be acted at; the reason says why. */
    record Refused(Reason reason) implements ActingScope { }

    /** Where a {@link Pinned} hospital came from. */
    enum Source {
        /** A {@code ?hospitalId=}, a body field or a path variable, via {@code narrowTo}. */
        REQUESTED,
        /** The {@code X-Hospital-Id} header. */
        HEADER,
        /** The caller holds exactly one hospital. */
        SOLE_ASSIGNMENT,
        /**
         * A context built without a source (worker threads and tests that set
         * {@code HospitalContextHolder} by hand). The two request producers
         * never produce it.
         */
        DEFAULT
    }

    /** Why a request has no hospital to act at. */
    enum Reason {
        /** The caller holds no hospital at all. */
        NO_HOSPITAL,
        /** The caller named a hospital they never held. */
        NOT_PERMITTED,
        /** The caller named a hospital they held once and no longer do (a stale chip, a revoked hospital). */
        NO_LONGER_PERMITTED,
        /** The caller holds several hospitals and named none. */
        AMBIGUOUS,
        /** A Keycloak principal with no {@code appUserId}, or one matching no local account. */
        NO_LOCAL_USER
    }
}
