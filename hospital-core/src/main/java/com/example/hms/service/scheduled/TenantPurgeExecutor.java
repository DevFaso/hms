package com.example.hms.service.scheduled;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Per-organization purge step extracted from {@link TenantPurgeJob} so each
 * call runs in its own transaction (Propagation.REQUIRES_NEW).
 *
 * <p>This split is load-bearing: the previous design wrapped the entire
 * sweep in a single {@code @Transactional} method on the job, so a single
 * org's save() failure could mark the whole transaction rollback-only and
 * silently revert the successful transitions earlier in the loop. With each
 * purge isolated in its own transaction, "one org failure does not break
 * the rest" actually holds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantPurgeExecutor {

    private final OrganizationRepository organizationRepository;
    private final AuditEventLogService auditEventLogService;

    /**
     * Transition a single organization to {@code PURGED} in an isolated
     * transaction. The {@code executeDeletion} flag is plumbed through but
     * is not yet wired to a cascade-delete in MVP-2 (logged as a warning).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executePurge(Organization org, boolean executeDeletion) {
        // Step 1 (future MVP): export org metadata + child data to long-term store.
        // Step 2 (future MVP, gated by executeDeletion): cascade-delete child rows.
        if (executeDeletion) {
            log.warn("[TENANT-PURGE] execute-deletion=true was set but data deletion is not implemented in MVP-2 "
                + "for org {}. State transition to PURGED will proceed; data is retained.", org.getId());
        }

        org.setLifecycleState(OrganizationLifecycleState.PURGED);
        org.setActive(false);
        org.setPurgedAt(Instant.now());
        organizationRepository.save(org);

        recordAudit(org);
        log.info("[TENANT-PURGE] Org {} ({}) transitioned to PURGED", org.getId(), org.getCode());
    }

    private void recordAudit(Organization org) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName("system:tenant-purge-job")
                .eventType(AuditEventType.TENANT_PURGED)
                .eventDescription("Scheduled purge executed for organization " + org.getCode())
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType("ORGANIZATION")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[TENANT-PURGE] Audit emission failed for purged org {}", org.getId(), ex);
        }
    }
}
