package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.AuditEventLog;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Filters for the audit-log reader. When the caller is not a super-admin in
 * global view (QA D25) every row must be anchored at one of the caller's
 * hospitals: through its assignment, or, for a SYSTEM row with no assignment
 * (a migration or a job that acted on one hospital's data), through its own
 * {@code hospitalId} (QA D786). Rows anchored nowhere (platform-wide events)
 * belong to the platform log and are never shown to a hospital-scoped reader.
 */
public final class AuditEventLogSpecifications {

    private static final String EVENT_TYPE = "eventType";

    private static final String EVENT_TIMESTAMP = "eventTimestamp";
    private static final char LIKE_ESCAPE = '\\';

    private AuditEventLogSpecifications() {
    }

    /** Anchored at one of {@code hospitalIds}; an empty set matches nothing. */
    public static Specification<AuditEventLog> anchoredAt(Set<UUID> hospitalIds) {
        return (root, query, cb) -> {
            if (hospitalIds == null || hospitalIds.isEmpty()) {
                return cb.disjunction();
            }
            if (query != null) {
                query.distinct(true);
            }
            // LEFT, not INNER: an inner join dropped every SYSTEM row (QA D786).
            Join<AuditEventLog, ?> assignment = root.join("assignment", JoinType.LEFT);
            return cb.or(
                assignment.get("hospital").get("id").in(hospitalIds),
                root.get("hospitalId").in(hospitalIds));
        };
    }

    /**
     * Every row, de-duplicated: the base of the platform-wide (global) read
     * when it is filtered, for the reason {@code AuditEventLogRepository#findAll}
     * gives (the EAGER user link must not multiply rows).
     */
    public static Specification<AuditEventLog> everyRow() {
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }
            return cb.conjunction();
        };
    }

    /**
     * QA D794: the audit page's free-text search, run by the database over
     * every row the reader may see (it used to filter the 20 rows on screen).
     * Matches, case-insensitively, the actor (display name, label or sign-in
     * name), the description, the entity type and the resource name; a blank
     * term matches everything. LIKE wildcards in the term are literal.
     */
    public static Specification<AuditEventLog> matching(String term) {
        return (root, query, cb) -> {
            if (term == null || term.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%";
            Join<AuditEventLog, ?> actor = root.join("user", JoinType.LEFT);
            return cb.or(
                cb.like(cb.lower(root.get("userName")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("actorLabel")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(actor.get("username")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("eventDescription")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("entityType")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("resourceName")), pattern, LIKE_ESCAPE));
        };
    }

    /** {@code eventType} alone; a null type matches everything. */
    public static Specification<AuditEventLog> ofType(AuditEventType type) {
        return (root, query, cb) -> type == null ? cb.conjunction() : cb.equal(root.get(EVENT_TYPE), type);
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static Specification<AuditEventLog> between(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return cb.conjunction();
            }
            if (from == null) {
                return cb.lessThanOrEqualTo(root.get(EVENT_TIMESTAMP), to);
            }
            if (to == null) {
                return cb.greaterThanOrEqualTo(root.get(EVENT_TIMESTAMP), from);
            }
            return cb.between(root.get(EVENT_TIMESTAMP), from, to);
        };
    }

    public static Specification<AuditEventLog> byUser(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<AuditEventLog> byEventType(AuditEventType type, AuditStatus status) {
        return (root, query, cb) -> status == null
            ? cb.equal(root.get(EVENT_TYPE), type)
            : cb.and(cb.equal(root.get(EVENT_TYPE), type), cb.equal(root.get("status"), status));
    }

    public static Specification<AuditEventLog> byTarget(String entityType, String resourceId) {
        return (root, query, cb) -> cb.and(
            cb.equal(cb.upper(root.get("entityType")), entityType.toUpperCase(Locale.ROOT)),
            cb.equal(root.get("resourceId"), resourceId));
    }
}
