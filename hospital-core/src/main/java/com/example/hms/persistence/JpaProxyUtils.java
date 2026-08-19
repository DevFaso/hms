package com.example.hms.persistence;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

/**
 * Defensive helpers for working with Hibernate lazy proxies whose referenced
 * row may have been hard-deleted (a "dangling FK"). Rather than letting a
 * downstream getter call propagate {@link EntityNotFoundException} /
 * {@link JpaObjectRetrievalFailureException} — which
 * {@code GlobalExceptionHandler.handleEntityNotFound} would translate into a
 * blanket HTTP 500 and break a whole list response over a single bad row —
 * the helpers here log a warn and substitute {@code null} so the rest of the
 * mapping completes.
 *
 * <p>The pattern was introduced inline in
 * {@code ConsultationServiceImpl} and {@code PrescriptionMapper}. This class
 * extracts it so additional mappers / services can adopt the same behaviour
 * without copy-pasting (and silently drifting) a fifth helper. Existing
 * inline copies are intentionally left in place to avoid widening the diff
 * of the bugfix that introduces this class — they can be migrated in a
 * follow-up cleanup.
 *
 * <p>Added in v1.0 / fix(super-admin/recent-activity) — dangling-FK 500.
 */
@Slf4j
public final class JpaProxyUtils {

    private JpaProxyUtils() {
        // Utility — not instantiable.
    }

    /**
     * Force-initialise a Hibernate lazy proxy. Returns {@code null} when the
     * referenced row was hard-deleted; otherwise returns the now-initialised
     * proxy (or the entity, if it was already a managed instance).
     *
     * <p>The {@code parentEntity}, {@code parentId}, and {@code association}
     * arguments are used only to shape the warn log line so operators can
     * locate the dangling FK in the database. They never appear in the
     * returned value, so they may be opaque identifiers — the caller does
     * not need to redact PII.
     *
     * @param proxyOrEntity   the lazy proxy (or already-initialised entity)
     * @param parentEntity    simple class name of the owning entity, e.g. {@code "LabOrder"}
     * @param parentId        identifier of the owning row (UUID, Long, …)
     * @param association     name of the field carrying the FK, e.g. {@code "patient"}
     * @return {@code proxyOrEntity} on success, {@code null} when the FK is dangling
     */
    public static <T> T safeInit(T proxyOrEntity, String parentEntity, Object parentId, String association) {
        if (proxyOrEntity == null) return null;
        try {
            Hibernate.initialize(proxyOrEntity);
            return proxyOrEntity;
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException ex) {
            log.warn("⚠️ {}({}) has a dangling FK on '{}' — referenced row was deleted. "
                    + "Returning null for this association; DB cleanup required.",
                parentEntity, parentId, association);
            return null;
        }
    }
}
