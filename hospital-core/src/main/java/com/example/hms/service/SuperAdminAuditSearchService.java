package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MVP-8: Cross-tenant audit search for super admins. Builds a JPA
 * Specification from the optional filters and delegates to
 * {@link com.example.hms.repository.AuditEventLogRepository}.
 *
 * <p>Surfaces the impersonator columns introduced by MVP-4 so a forensic
 * search can find every action taken under an impersonation token.
 */
public interface SuperAdminAuditSearchService {

    /**
     * Run a paged search across {@code AuditEventLog}. All filter args are
     * optional — null / empty values are skipped.
     *
     * @param userId actor user id
     * @param userNameLike case-insensitive substring against denormalized userName
     * @param eventTypes inclusive set of event types
     * @param status optional event status
     * @param hospitalId hospital scope
     * @param organizationId organization scope (joined via assignment.hospital.organization)
     * @param impersonatorUserId real super-admin user id from MVP-4
     * @param entityType target entity type (case-insensitive)
     * @param resourceId target resource id (string)
     * @param fromDate inclusive lower bound on eventTimestamp
     * @param toDate inclusive upper bound on eventTimestamp
     * @param pageable page + sort directives (defaults to eventTimestamp desc)
     */
    AuditSearchPageDTO search(
        UUID userId,
        String userNameLike,
        List<AuditEventType> eventTypes,
        AuditStatus status,
        UUID hospitalId,
        UUID organizationId,
        UUID impersonatorUserId,
        String entityType,
        String resourceId,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable
    );
}
