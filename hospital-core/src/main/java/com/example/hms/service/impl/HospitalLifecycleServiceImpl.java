package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.HospitalLifecycleResponseDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.HospitalLifecycleService;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.MfaService;
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

/**
 * Hospital-level lifecycle state-machine impl (MVP-c batch).
 *
 * <p>Closely mirrors {@code OrganizationLifecycleServiceImpl} — same
 * transition rules, same MFA step-up posture, same audit / fail-closed
 * semantics. Differences are scoped to the entity (Hospital vs.
 * Organization) and the audit event names (HOSPITAL_* vs. TENANT_*).
 *
 * <p>Properties {@code hms.hospital-lifecycle.require-mfa} and
 * {@code …require-mfa-strict} mirror the org-level counterparts; both
 * default to the same values so a super admin enrolment posture
 * applies uniformly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class HospitalLifecycleServiceImpl implements HospitalLifecycleService {

    private static final String ENTITY_TYPE_HOSPITAL = "HOSPITAL";
    private static final long DEFAULT_PURGE_GRACE_DAYS = 30;

    private static final String ACTION_SUSPEND = "suspend";
    private static final String ACTION_RESTORE = "restore";
    private static final String ACTION_ARCHIVE = "archive";
    private static final String ACTION_SCHEDULE_PURGE = "schedule purge";
    private static final String ACTION_CANCEL_PURGE = "cancel purge";

    private static final Set<HospitalLifecycleState> SUSPENDABLE = EnumSet.of(
        HospitalLifecycleState.ACTIVE);
    private static final Set<HospitalLifecycleState> RESTORABLE = EnumSet.of(
        HospitalLifecycleState.SUSPENDED, HospitalLifecycleState.ARCHIVED);
    private static final Set<HospitalLifecycleState> ARCHIVABLE = EnumSet.of(
        HospitalLifecycleState.ACTIVE, HospitalLifecycleState.SUSPENDED);
    private static final Set<HospitalLifecycleState> PURGE_SCHEDULABLE = EnumSet.of(
        HospitalLifecycleState.ARCHIVED);
    private static final Set<HospitalLifecycleState> PURGE_CANCELLABLE = EnumSet.of(
        HospitalLifecycleState.PENDING_PURGE);

    private final HospitalRepository hospitalRepository;
    private final AuditEventLogService auditEventLogService;
    private final HospitalLifecycleStatusService lifecycleStatusService;
    private final MfaService mfaService;

    @Value("${hms.hospital-lifecycle.require-mfa:true}")
    private boolean requireMfa;

    @Value("${hms.hospital-lifecycle.require-mfa-strict:false}")
    private boolean requireMfaStrict;

    @Override
    @Transactional(readOnly = true)
    public HospitalLifecycleResponseDTO getLifecycle(UUID hospitalId) {
        return toResponse(loadOrThrow(hospitalId));
    }

    @Override
    public HospitalLifecycleResponseDTO suspend(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Hospital hospital = loadOrThrow(hospitalId);
        requireTransition(hospital, SUSPENDABLE, ACTION_SUSPEND);
        String reason = requireReason(request, ACTION_SUSPEND);
        requireStepUp(ACTION_SUSPEND, mfaToken);

        hospital.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        // Mirror onto legacy `active` so default-visibility queries hide
        // suspended hospitals immediately, not only via JWT login block.
        hospital.setActive(false);
        hospital.setSuspendedAt(Instant.now());
        hospital.setSuspendedBy(currentActorId());
        hospital.setSuspensionReason(reason);
        hospitalRepository.save(hospital);
        invalidateStatusCache();

        recordAudit(hospital, AuditEventType.HOSPITAL_SUSPENDED, "Hospital suspended: " + reason);
        return toResponse(hospital);
    }

    @Override
    public HospitalLifecycleResponseDTO restore(UUID hospitalId, TenantLifecycleActionRequestDTO request) {
        Hospital hospital = loadOrThrow(hospitalId);
        requireTransition(hospital, RESTORABLE, ACTION_RESTORE);

        HospitalLifecycleState previous = hospital.getLifecycleState();
        hospital.setLifecycleState(HospitalLifecycleState.ACTIVE);
        hospital.setActive(true);
        hospitalRepository.save(hospital);
        invalidateStatusCache();

        String description = "Hospital restored from " + previous
            + (request != null && request.getReason() != null ? ": " + request.getReason() : "");
        recordAudit(hospital, AuditEventType.HOSPITAL_RESTORED, description);
        return toResponse(hospital);
    }

    @Override
    public HospitalLifecycleResponseDTO archive(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Hospital hospital = loadOrThrow(hospitalId);
        requireTransition(hospital, ARCHIVABLE, ACTION_ARCHIVE);
        String reason = requireReason(request, ACTION_ARCHIVE);
        requireStepUp(ACTION_ARCHIVE, mfaToken);

        hospital.setLifecycleState(HospitalLifecycleState.ARCHIVED);
        hospital.setActive(false);
        hospital.setArchivedAt(Instant.now());
        hospital.setArchivedBy(currentActorId());
        hospital.setArchiveReason(reason);
        hospitalRepository.save(hospital);
        invalidateStatusCache();

        recordAudit(hospital, AuditEventType.HOSPITAL_ARCHIVED, "Hospital archived: " + reason);
        return toResponse(hospital);
    }

    @Override
    public HospitalLifecycleResponseDTO schedulePurge(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken) {
        Hospital hospital = loadOrThrow(hospitalId);
        requireTransition(hospital, PURGE_SCHEDULABLE, ACTION_SCHEDULE_PURGE);
        String reason = requireReason(request, ACTION_SCHEDULE_PURGE);
        requireStepUp(ACTION_SCHEDULE_PURGE, mfaToken);

        Instant scheduledFor = request.getPurgeScheduledFor() != null
            ? request.getPurgeScheduledFor()
            : Instant.now().plus(DEFAULT_PURGE_GRACE_DAYS, ChronoUnit.DAYS);

        if (scheduledFor.isBefore(Instant.now())) {
            throw new BusinessRuleException("Purge cannot be scheduled in the past.");
        }

        hospital.setLifecycleState(HospitalLifecycleState.PENDING_PURGE);
        hospital.setPurgeScheduledFor(scheduledFor);
        hospital.setPurgeScheduledBy(currentActorId());
        hospital.setPurgeReason(reason);
        hospitalRepository.save(hospital);
        invalidateStatusCache();

        recordAudit(hospital, AuditEventType.HOSPITAL_PURGE_SCHEDULED,
            "Purge scheduled for " + scheduledFor + ": " + reason);
        return toResponse(hospital);
    }

    @Override
    public HospitalLifecycleResponseDTO cancelPurge(UUID hospitalId, TenantLifecycleActionRequestDTO request) {
        Hospital hospital = loadOrThrow(hospitalId);
        requireTransition(hospital, PURGE_CANCELLABLE, ACTION_CANCEL_PURGE);

        hospital.setLifecycleState(HospitalLifecycleState.ARCHIVED);
        hospital.setPurgeScheduledFor(null);
        hospital.setPurgeScheduledBy(null);
        hospital.setPurgeReason(null);
        hospitalRepository.save(hospital);
        invalidateStatusCache();

        String description = "Purge cancelled"
            + (request != null && request.getReason() != null ? ": " + request.getReason() : "");
        recordAudit(hospital, AuditEventType.HOSPITAL_PURGE_CANCELLED, description);
        return toResponse(hospital);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Hospital loadOrThrow(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + hospitalId));
    }

    private void requireTransition(Hospital hospital, Set<HospitalLifecycleState> allowed, String action) {
        if (!allowed.contains(hospital.getLifecycleState())) {
            throw new BusinessRuleException(
                "Cannot " + action + " hospital in state " + hospital.getLifecycleState()
                    + " (allowed: " + allowed + ")");
        }
    }

    private String requireReason(TenantLifecycleActionRequestDTO request, String action) {
        String reason = request != null ? request.getReason() : null;
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessRuleException("A reason is required to " + action + " a hospital.");
        }
        return reason.trim();
    }

    private void requireStepUp(String action, String mfaToken) {
        if (!requireMfa) {
            return;
        }
        UUID actorId = currentActorId();
        if (actorId == null) {
            throw new UnauthorizedException("mfa_required: cannot resolve actor for " + action);
        }
        boolean enrolled;
        try {
            enrolled = mfaService.isMfaEnabled(actorId);
        } catch (RuntimeException ex) {
            log.warn("[HOSPITAL-LIFECYCLE] MFA enrollment lookup failed for actor {}: {}",
                actorId, ex.getMessage());
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
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .userName(currentActorUsername())
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .eventDescription("Destructive hospital-lifecycle action '" + action
                    + "' performed without MFA step-up (actor not enrolled, non-strict mode).")
                .entityType(ENTITY_TYPE_HOSPITAL)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[HOSPITAL-LIFECYCLE] Failed to audit MFA-bypass event", ex);
        }
    }

    private UUID currentActorId() {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return context.getPrincipalUserId();
    }

    private void invalidateStatusCache() {
        try {
            lifecycleStatusService.invalidate();
        } catch (RuntimeException ex) {
            // Cache invalidation is best-effort — a stale cache only delays
            // the new state taking effect by the cache TTL (30s).
            log.warn("[HOSPITAL-LIFECYCLE] Failed to invalidate hospital-lifecycle-status cache: {}",
                ex.getMessage());
        }
    }

    private String currentActorUsername() {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return context.getPrincipalUsername();
    }

    private void recordAudit(Hospital hospital, AuditEventType eventType, String description) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentActorId())
                .userName(currentActorUsername())
                .eventType(eventType)
                .eventDescription(description)
                .resourceId(hospital.getId().toString())
                .resourceName(hospital.getName())
                .entityType(ENTITY_TYPE_HOSPITAL)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[HOSPITAL-LIFECYCLE] Failed to record audit event {} for hospital {}",
                eventType, hospital.getId(), ex);
        }
    }

    private HospitalLifecycleResponseDTO toResponse(Hospital hospital) {
        HospitalLifecycleState state = hospital.getLifecycleState();
        return HospitalLifecycleResponseDTO.builder()
            .hospitalId(hospital.getId())
            .hospitalName(hospital.getName())
            .hospitalCode(hospital.getCode())
            .organizationId(hospital.getOrganization() != null ? hospital.getOrganization().getId() : null)
            .lifecycleState(state)
            .suspendedAt(hospital.getSuspendedAt())
            .suspendedBy(hospital.getSuspendedBy())
            .suspensionReason(hospital.getSuspensionReason())
            .archivedAt(hospital.getArchivedAt())
            .archivedBy(hospital.getArchivedBy())
            .archiveReason(hospital.getArchiveReason())
            .purgeScheduledFor(hospital.getPurgeScheduledFor())
            .purgeScheduledBy(hospital.getPurgeScheduledBy())
            .purgeReason(hospital.getPurgeReason())
            .purgedAt(hospital.getPurgedAt())
            .canSuspend(SUSPENDABLE.contains(state))
            .canRestore(RESTORABLE.contains(state))
            .canArchive(ARCHIVABLE.contains(state))
            .canSchedulePurge(PURGE_SCHEDULABLE.contains(state))
            .canCancelPurge(PURGE_CANCELLABLE.contains(state))
            .build();
    }
}
