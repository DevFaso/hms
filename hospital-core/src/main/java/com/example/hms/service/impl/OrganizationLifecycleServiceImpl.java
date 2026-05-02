package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.OrganizationLifecycleService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    // Action labels — used for transition validation, reason validation, and
    // MFA step-up messages. Kept as constants to dedupe per Sonar S1192.
    private static final String ACTION_SUSPEND = "suspend";
    private static final String ACTION_RESTORE = "restore";
    private static final String ACTION_ARCHIVE = "archive";
    private static final String ACTION_SCHEDULE_PURGE = "schedule purge";
    private static final String ACTION_CANCEL_PURGE = "cancel purge";

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
    private final MfaService mfaService;

    /**
     * When true, destructive transitions (suspend/archive/schedule-purge) require
     * a valid X-Mfa-Token header from any actor that has MFA enrolled. Default on
     * — flip to {@code false} only for emergency rollback. Even when on, actors
     * without MFA enrollment fall back to typed-confirm + audit (see
     * {@code require-mfa-strict} for the no-fallback variant).
     */
    @Value("${hms.tenant-lifecycle.require-mfa:true}")
    private boolean requireMfa;

    /**
     * When true, an actor without MFA enrolled is rejected outright instead of
     * being allowed through with audit. Off by default so unenrolled super
     * admins are not locked out of the lifecycle controls before they enrol.
     */
    @Value("${hms.tenant-lifecycle.require-mfa-strict:false}")
    private boolean requireMfaStrict;

    @Override
    @Transactional(readOnly = true)
    public TenantLifecycleResponseDTO getLifecycle(UUID organizationId) {
        Organization org = loadOrThrow(organizationId);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO suspend(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, SUSPENDABLE, ACTION_SUSPEND);
        String reason = requireReason(request, ACTION_SUSPEND);
        requireStepUp(ACTION_SUSPEND, mfaToken);

        org.setLifecycleState(OrganizationLifecycleState.SUSPENDED);
        // The legacy `active` flag still drives default-visibility queries
        // (findByActiveTrue, findAllActiveWithHospitals). Flip it so a
        // suspended tenant disappears from default org lists immediately,
        // not only from the JWT login path.
        org.setActive(false);
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
        requireTransition(org, RESTORABLE, ACTION_RESTORE);

        OrganizationLifecycleState previous = org.getLifecycleState();
        org.setLifecycleState(OrganizationLifecycleState.ACTIVE);
        // Re-mirror onto the legacy `active` flag — restore from either
        // SUSPENDED or ARCHIVED brings the org back into default lists.
        org.setActive(true);
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
    public TenantLifecycleResponseDTO archive(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, ARCHIVABLE, ACTION_ARCHIVE);
        String reason = requireReason(request, ACTION_ARCHIVE);
        requireStepUp(ACTION_ARCHIVE, mfaToken);

        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        // Same rationale as suspend — flip the legacy `active` flag so
        // archived tenants are also hidden from any default-visibility query.
        org.setActive(false);
        org.setArchivedAt(Instant.now());
        org.setArchivedBy(currentActorId());
        org.setArchiveReason(reason);
        organizationRepository.save(org);
        invalidateStatusCache();

        recordAudit(org, AuditEventType.TENANT_ARCHIVED, "Organization archived: " + reason);
        return toResponse(org);
    }

    @Override
    public TenantLifecycleResponseDTO schedulePurge(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Organization org = loadOrThrow(organizationId);
        requireTransition(org, PURGE_SCHEDULABLE, ACTION_SCHEDULE_PURGE);
        String reason = requireReason(request, ACTION_SCHEDULE_PURGE);
        requireStepUp(ACTION_SCHEDULE_PURGE, mfaToken);

        // `request` is guaranteed non-null here — requireReason() above throws
        // otherwise. Only the per-call override on getPurgeScheduledFor() is optional.
        Instant scheduledFor = request.getPurgeScheduledFor() != null
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
        requireTransition(org, PURGE_CANCELLABLE, ACTION_CANCEL_PURGE);

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

    /**
     * Step-up MFA gate for destructive actions. Three behaviours, controlled by
     * two properties:
     *
     * <ul>
     *   <li>{@code requireMfa=false} → no-op (emergency rollback only).
     *   <li>{@code requireMfa=true}, actor has MFA enrolled → token must be
     *       present and valid; rejects with 401 {@code mfa_required} otherwise.
     *   <li>{@code requireMfa=true}, actor has no MFA enrollment → if
     *       {@code requireMfaStrict=true}, reject; otherwise audit a
     *       SECURITY_ALERT_TRIGGERED event and allow through (so unenrolled
     *       super admins are not locked out before they enrol).
     * </ul>
     */
    private void requireStepUp(String action, String mfaToken) {
        if (!requireMfa) {
            return;
        }
        UUID actorId = currentActorId();
        if (actorId == null) {
            // No principal — should not happen on an authenticated endpoint, but
            // failing closed is the right call.
            throw new UnauthorizedException("mfa_required: cannot resolve actor for " + action);
        }
        boolean enrolled;
        try {
            enrolled = mfaService.isMfaEnabled(actorId);
        } catch (RuntimeException ex) {
            log.warn("[TENANT-LIFECYCLE] MFA enrollment lookup failed for actor {}: {}",
                actorId, ex.getMessage());
            // Fail closed when we cannot resolve the actor's MFA status.
            throw new UnauthorizedException("mfa_required: enrollment lookup unavailable");
        }
        if (enrolled) {
            if (mfaToken == null || mfaToken.isBlank() || !mfaService.verifyCode(actorId, mfaToken)) {
                throw new UnauthorizedException("mfa_required: invalid or missing X-Mfa-Token for " + action);
            }
            return;
        }
        if (requireMfaStrict) {
            throw new UnauthorizedException("mfa_required: actor must enrol MFA before performing " + action);
        }
        // Unenrolled actor passing through under non-strict mode — audit it so
        // ops has a paper trail of every destructive action that bypassed MFA.
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .userName(currentActorUsername())
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .eventDescription("Destructive tenant-lifecycle action '" + action
                    + "' performed without MFA step-up (actor not enrolled, non-strict mode).")
                .entityType(ENTITY_TYPE_ORGANIZATION)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[TENANT-LIFECYCLE] Failed to audit MFA-bypass event", ex);
        }
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
