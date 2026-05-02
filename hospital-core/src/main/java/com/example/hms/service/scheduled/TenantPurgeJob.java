package com.example.hms.service.scheduled;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Nightly sweep that transitions due {@code PENDING_PURGE} organizations to
 * {@code PURGED}. Runs at 03:00 in the deployment timezone.
 *
 * <p><b>Scope (MVP-2):</b> this job only flips the lifecycle state and writes
 * an audit event. The actual deletion of org-scoped data (patients, hospitals,
 * encounters, …) is gated behind a separate property so it is opt-in per
 * environment:
 * <ul>
 *   <li>{@code hms.tenant-purge.enabled} (default: {@code false}) — toggles
 *       the entire job, including state transitions.
 *   <li>{@code hms.tenant-purge.execute-deletion} (default: {@code false}) —
 *       allows the job to actually delete child rows. <b>Not implemented in
 *       MVP-2</b>; it is recorded here as the explicit next-step extension
 *       point so a follow-up MVP can wire in the GDPR export bucket and the
 *       cascade deletes.
 * </ul>
 *
 * <p>The 30-day grace window is enforced when the purge is scheduled (see
 * {@code OrganizationLifecycleService.schedulePurge}); this job only runs on
 * rows whose {@code purge_scheduled_for} is already in the past.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantPurgeJob {

    private final OrganizationRepository organizationRepository;
    private final AuditEventLogService auditEventLogService;
    private final OrganizationLifecycleStatusService lifecycleStatusService;

    @Value("${hms.tenant-purge.enabled:false}")
    private boolean enabled;

    @Value("${hms.tenant-purge.execute-deletion:false}")
    private boolean executeDeletion;

    /** Cron: every day at 03:00. Configurable via {@code hms.tenant-purge.cron}. */
    @Scheduled(cron = "${hms.tenant-purge.cron:0 0 3 * * *}")
    @Transactional
    public void runSweep() {
        if (!enabled) {
            log.debug("[TENANT-PURGE] Skipping sweep — disabled in this environment");
            return;
        }

        Instant now = Instant.now();
        List<Organization> due = organizationRepository.findDuePurges(now);

        if (due.isEmpty()) {
            log.debug("[TENANT-PURGE] Sweep at {} — no due purges", now);
            return;
        }

        log.info("[TENANT-PURGE] Sweep at {} — processing {} due organization(s)", now, due.size());

        for (Organization org : due) {
            try {
                executePurge(org);
            } catch (RuntimeException ex) {
                // Don't let one failed org break the rest of the sweep.
                log.error("[TENANT-PURGE] Failed to purge org {}: {}", org.getId(), ex.getMessage(), ex);
            }
        }

        lifecycleStatusService.invalidate();
    }

    private void executePurge(Organization org) {
        // Step 1 (future MVP): export org metadata + child data to long-term store.
        // Step 2 (future MVP, gated by executeDeletion): cascade-delete child rows.
        if (executeDeletion) {
            log.warn("[TENANT-PURGE] execute-deletion=true was set but data deletion is not implemented in MVP-2 "
                + "for org {}. State transition to PURGED will proceed; data is retained.", org.getId());
        }

        org.setLifecycleState(OrganizationLifecycleState.PURGED);
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
