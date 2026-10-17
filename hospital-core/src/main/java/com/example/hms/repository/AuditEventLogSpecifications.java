package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.AuditEventLog;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Filters for the audit-log reader when the caller is not a super-admin in
 * global view (QA D25): every row must be anchored at one of the caller's
 * hospitals through its assignment. Rows with no assignment (system actors,
 * platform-wide events) belong to the platform log and are never shown to a
 * hospital-scoped reader.
 */
public final class AuditEventLogSpecifications {

    private static final String EVENT_TIMESTAMP = "eventTimestamp";

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
            return root.join("assignment", JoinType.INNER).get("hospital").get("id").in(hospitalIds);
        };
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
            ? cb.equal(root.get("eventType"), type)
            : cb.and(cb.equal(root.get("eventType"), type), cb.equal(root.get("status"), status));
    }

    public static Specification<AuditEventLog> byTarget(String entityType, String resourceId) {
        return (root, query, cb) -> cb.and(
            cb.equal(cb.upper(root.get("entityType")), entityType.toUpperCase(Locale.ROOT)),
            cb.equal(root.get("resourceId"), resourceId));
    }
}
