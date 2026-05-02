package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.OrganizationLifecycleService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrganizationLifecycleServiceImpl implements OrganizationLifecycleService {

    private static final String ENTITY_TYPE_ORGANIZATION = "ORGANIZATION";
    private static final long DEFAULT_PURGE_GRACE_DAYS = 30;

    private static final Set<OrganizationLifecycleState> SUSPENDABLE = EnumSet.of(
        OrganizationLifecycleState.ACTIVE
    );
    private static final Set<OrganizationLifecycleState> RESTORABLE = EnumSet.of(
        OrganizationLifecycleState.SUSPENDED,
        OrganizationLifecycleState.ARCHIVED
    );
    private static final Set<OrganizationLifecycleState> ARCHIVABLE = EnumSet.of(
        OrganizationLifecycleState.ACTIVE,
        OrganizationLifecycleState.SUSPENDED
    );
    private static final Set<OrganizationLifecycleState> PURGE_SCHEDULABLE = EnumSet.of(
        OrganizationLifecycleState.ARCHIVED
    );
    private static final Set<OrganizationLifecycleState> PURGE_CANCELLABLE = EnumSet.of(
        OrganizationLifecycleState.PENDING_PURGE
    );

    private final OrganizationRepository organizationRepository;
    private final AuditEventLogService auditEventLogService;
    private final OrganizationLifecycleStatusService lifecycleStatusService;

    @Override
    @Transactional(readOnly = true)
    public TenantLifecycleResponseDTO getLifecycle(UUID organizationId) {
        Organization org = loadOrThrow(organizationId);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO suspend(UUID organizationId, TenantLifecycleActionRequestDTO request) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, SUSPENDABLE, "suspend");
        String reason = requireReason(request, "suspend");

        org.setLifecycleState(OrganizationLifecycleState.SUSPENDED);
        org.setSuspendedAt(Instant.now());
        org.setSuspendedBy(currentActorId());
        org.setSuspensionReason(reason);
        organizationRepository.save(org);
        invalidateStatusCache();

        recordAudit(org, AuditEventType.TENANT_SUSPENDED, "Organization suspended: " + reason);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO restore(UUID organizationId, TenantLifecycleActionRequestDTO request) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, RESTORABLE, "restore");

        OrganizationLifecycleState previous = org.getLifecycleState();
        org.setLifecycleState(OrganizationLifecycleState.ACTIVE);
        // Snapshot fields stay so the audit chain remains visible; only the
        // current state is reset. AuditEventLog has the history.
        organizationRepository.save(org);
        invalidateStatusCache();

        String description = "Organization restored from " + previous + (request != null && request.getReason() != null
            ? ": " + request.getReason() : "");
        recordAudit(org, AuditEventType.TENANT_RESTORED, description);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO archive(UUID organizationId, TenantLifecycleActionRequestDTO request) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, ARCHIVABLE, "archive");
        String reason = requireReason(request, "archive");

        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        org.setArchivedAt(Instant.now());
        org.setArchivedBy(currentActorId());
        org.setArchiveReason(reason);
        organizationRepository.save(org);
        invalidateStatusCache();

        recordAudit(org, AuditEventType.TENANT_ARCHIVED, "Organization archived: " + reason);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO schedulePurge(UUID organizationId, TenantLifecycleActionRequestDTO request) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, PURGE_SCHEDULABLE, "schedule purge");
        String reason = requireReason(request, "schedule purge");

        Instant scheduledFor = (request != null && request.getPurgeScheduledFor() != null)
            ? request.getPurgeScheduledFor()
            : Instant.now().plus(DEFAULT_PURGE_GRACE_DAYS, ChronoUnit.DAYS);

        if (scheduledFor.isBefore(Instant.now())) {
            throw new BusinessRuleException("Purge cannot be scheduled in the past.");
        }

        org.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        org.setPurgeScheduledFor(scheduledFor);
        org.setPurgeScheduledBy(currentActorId());
        org.setPurgeReason(reason);
        organizationRepository.save(org);
        invalidateStatusCache();

        recordAudit(org, AuditEventType.TENANT_PURGE_SCHEDULED,
            "Purge scheduled for " + scheduledFor + ": " + reason);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO cancelPurge(UUID organizationId, TenantLifecycleActionRequestDTO request) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, PURGE_CANCELLABLE, "cancel purge");

        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        org.setPurgeScheduledFor(null);
        org.setPurgeScheduledBy(null);
        org.setPurgeReason(null);
        organizationRepository.save(org);
        invalidateStatusCache();

        String description = "Purge cancelled" + (request != null && request.getReason() != null
            ? ": " + request.getReason() : "");
        recordAudit(org, AuditEventType.TENANT_PURGE_CANCELLED, description);
        return toResponse(org);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Organization loadOrThrow(UUID organizationId) {
        return organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Organization not found: " + organizationId));
    }

    private void requireTransition(Organization org, Set<OrganizationLifecycleState> allowed, String action) {
        if (!allowed.contains(org.getLifecycleState())) {
            throw new BusinessRuleException(
                "Cannot " + action + " organization in state " + org.getLifecycleState()
                    + " (allowed: " + allowed + ")");
        }
    }

    private String requireReason(TenantLifecycleActionRequestDTO request, String action) {
        String reason = request != null ? request.getReason() : null;
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessRuleException("A reason is required to " + action + " an organization.");
        }
        return reason.trim();
    }

    private UUID currentActorId() {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return context.getPrincipalUserId();
    }

    private String currentActorUsername() {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return context.getPrincipalUsername();
    }

    private void invalidateStatusCache() {
        try {
            lifecycleStatusService.invalidate();
        } catch (RuntimeException ex) {
            // Cache invalidation is best-effort — a stale cache only delays
            // the new state taking effect by the cache TTL.
            log.warn("[TENANT-LIFECYCLE] Failed to invalidate lifecycle-status cache: {}",
                ex.getMessage());
        }
    }

    private void recordAudit(Organization org, AuditEventType eventType, String description) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentActorId())
                .userName(currentActorUsername())
                .eventType(eventType)
                .eventDescription(description)
                .resourceId(org.getId().toString())
                .resourceName(org.getName())
                .entityType(ENTITY_TYPE_ORGANIZATION)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            // Audit failure must not roll back the lifecycle transition — the
            // transition is the source of truth and the audit log is for
            // observability. Log loudly so ops sees the gap.
            log.error("[TENANT-LIFECYCLE] Failed to record audit event {} for org {}",
                eventType, org.getId(), ex);
        }
    }

    private TenantLifecycleResponseDTO toResponse(Organization org) {
        OrganizationLifecycleState state = org.getLifecycleState();
        return TenantLifecycleResponseDTO.builder()
            .organizationId(org.getId())
            .organizationName(org.getName())
            .organizationCode(org.getCode())
            .lifecycleState(state)
            .suspendedAt(org.getSuspendedAt())
            .suspendedBy(org.getSuspendedBy())
            .suspensionReason(org.getSuspensionReason())
            .archivedAt(org.getArchivedAt())
            .archivedBy(org.getArchivedBy())
            .archiveReason(org.getArchiveReason())
            .purgeScheduledFor(org.getPurgeScheduledFor())
            .purgeScheduledBy(org.getPurgeScheduledBy())
            .purgeReason(org.getPurgeReason())
            .purgedAt(org.getPurgedAt())
            .canSuspend(SUSPENDABLE.contains(state))
            .canRestore(RESTORABLE.contains(state))
            .canArchive(ARCHIVABLE.contains(state))
            .canSchedulePurge(PURGE_SCHEDULABLE.contains(state))
            .canCancelPurge(PURGE_CANCELLABLE.contains(state))
            .build();
    }
}
