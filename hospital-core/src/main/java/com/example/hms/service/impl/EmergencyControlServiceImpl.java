package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyActionResponseDTO;
import com.example.hms.payload.dto.superadmin.EmergencyBroadcastRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceLogoutRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceMfaRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyKillFeatureRequestDTO;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.security.GlobalSessionRevocationService;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.EmergencyControlService;
import com.example.hms.service.FeatureFlagService;
import com.example.hms.service.MfaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP-7 implementation. Force-logout-all bumps the global min-iat via
 * {@link GlobalSessionRevocationService}; the JWT filter then rejects
 * tokens issued before the bump. Kill-feature flips the
 * {@link FeatureFlagService} override to {@code enabled=false}.
 * Force-MFA-reenrol clears every {@code UserMfaEnrollment} +
 * {@code MfaBackupCode} row for the listed users (or all enrolled users
 * when the list is empty), forcing them through the enrolment flow on
 * their next login. Broadcast publishes a STOMP frame to
 * {@code /topic/emergency-broadcast}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyControlServiceImpl implements EmergencyControlService {

    private static final String BROADCAST_TOPIC = "/topic/emergency-broadcast";
    private static final String DEFAULT_ENVIRONMENT = "default";

    private final GlobalSessionRevocationService revocationService;
    private final FeatureFlagService featureFlagService;
    private final UserMfaEnrollmentRepository mfaEnrollmentRepository;
    private final MfaBackupCodeRepository mfaBackupCodeRepository;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MfaService mfaService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** Reuses the same strict-mode flag MVP-4 uses for impersonation start. */
    @Value("${hms.support-impersonation.require-mfa-strict:false}")
    private boolean requireMfaStrict;

    @Override
    @Transactional
    public EmergencyActionResponseDTO forceLogoutAll(EmergencyForceLogoutRequestDTO request, String mfaToken) {
        UUID actorId = currentUserId();
        String actorUsername = SecurityUtils.getCurrentUsername();
        verifyMfaStepUp(actorId, mfaToken, "force-logout-all");
        Instant takenAt = revocationService.revokeAll(actorId, actorUsername, request.getReason());
        audit(actorId, actorUsername, "EMERGENCY_FORCE_LOGOUT_ALL", request.getReason());
        return EmergencyActionResponseDTO.builder()
            .action("FORCE_LOGOUT_ALL")
            .takenAt(takenAt)
            .actorUsername(actorUsername)
            .affectedRows(0)
            .message("All sessions revoked — every JWT issued before " + takenAt + " is now invalid.")
            .build();
    }

    @Override
    @Transactional
    public EmergencyActionResponseDTO killFeature(EmergencyKillFeatureRequestDTO request, String mfaToken) {
        String actorUsername = SecurityUtils.getCurrentUsername();
        verifyMfaStepUp(currentUserId(), mfaToken, "kill-feature");
        featureFlagService.upsertOverride(
            request.getFlagKey(),
            false,
            "MVP-7 emergency kill: " + request.getReason(),
            actorUsername,
            DEFAULT_ENVIRONMENT,
            Locale.ROOT
        );
        audit(currentUserId(), actorUsername, "EMERGENCY_KILL_FEATURE",
            "Killed feature flag '" + request.getFlagKey() + "': " + request.getReason());
        return EmergencyActionResponseDTO.builder()
            .action("KILL_FEATURE")
            .takenAt(Instant.now())
            .actorUsername(actorUsername)
            .affectedRows(1)
            .message("Feature flag '" + request.getFlagKey() + "' is now disabled platform-wide.")
            .build();
    }

    @Override
    @Transactional
    public EmergencyActionResponseDTO forceMfaReenrol(EmergencyForceMfaRequestDTO request, String mfaToken) {
        String actorUsername = SecurityUtils.getCurrentUsername();
        verifyMfaStepUp(currentUserId(), mfaToken, "force-mfa-reenrol");
        List<UUID> targets = request.getUserIds();
        if (targets == null || targets.isEmpty()) {
            if (!Boolean.TRUE.equals(request.getResetAll())) {
                throw new BusinessException(
                    "Refusing a platform-wide MFA reset: no userIds were given and resetAll is not true. "
                        + "Name the users, or set resetAll=true to reset every enrolled user"
                        + (request.getHospitalId() != null ? " at the given hospital." : " on the platform."));
            }
            // Fall back to every user with an active enrolment row.
            // UserMfaEnrollment exposes the user via the JPA association — there
            // is no flat `userId` field on the entity, so navigate through getUser().
            targets = mfaEnrollmentRepository.findAll().stream()
                .map(UserMfaEnrollment::getUser)
                .filter(java.util.Objects::nonNull)
                .map(User::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        }

        // MVP-7b: optional hospital scope. Intersect the target list with
        // users who have an active assignment to the named hospital so a
        // platform-wide reset can be narrowed to one site (e.g. one
        // hospital reports a phishing incident — reset only its staff).
        UUID hospitalScope = request.getHospitalId();
        if (hospitalScope != null) {
            java.util.Set<UUID> hospitalUserIds = assignmentRepository
                .findActiveUserIdsByHospitalId(hospitalScope);
            targets = targets.stream()
                .filter(hospitalUserIds::contains)
                .toList();
        }

        int affected = 0;
        // Snapshot the resolved target list so the audit + response message
        // stay consistent even if the loop encounters a user with no rows.
        List<UUID> resolvedTargets = targets;
        for (UUID userId : resolvedTargets) {
            List<UserMfaEnrollment> enrolments = mfaEnrollmentRepository.findByUserId(userId);
            if (!enrolments.isEmpty()) {
                mfaEnrollmentRepository.deleteAll(enrolments);
                affected += enrolments.size();
            }
            mfaBackupCodeRepository.deleteAllByUserId(userId);
        }

        String scopeSuffix = hospitalScope != null
            ? " (scoped to hospital " + hospitalScope + ")"
            : "";
        audit(currentUserId(), actorUsername, "EMERGENCY_FORCE_MFA_REENROL",
            "Cleared MFA for " + resolvedTargets.size() + " user(s)" + scopeSuffix
                + ": " + request.getReason());
        return EmergencyActionResponseDTO.builder()
            .action("FORCE_MFA_REENROL")
            .takenAt(Instant.now())
            .actorUsername(actorUsername)
            .affectedRows(affected)
            .message("Cleared " + affected + " MFA enrolment row(s) for "
                + resolvedTargets.size() + " user(s)" + scopeSuffix + ".")
            .build();
    }

    @Override
    @Transactional
    public EmergencyActionResponseDTO broadcast(EmergencyBroadcastRequestDTO request, String mfaToken) {
        String actorUsername = SecurityUtils.getCurrentUsername();
        verifyMfaStepUp(currentUserId(), mfaToken, "broadcast");
        String severity = request.getSeverity() == null ? "INFO" : request.getSeverity();

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "EMERGENCY_BROADCAST");
        payload.put("severity", severity);
        payload.put("message", request.getMessage());
        payload.put("issuedBy", actorUsername);
        payload.put("issuedAt", Instant.now().toString());

        try {
            messagingTemplate.convertAndSend(BROADCAST_TOPIC, payload);
        } catch (RuntimeException ex) {
            // Don't let a transient broker failure swallow the audit trail —
            // the action is still recorded as an attempt.
            log.warn("[EMERGENCY] Failed to publish broadcast to {}: {}", BROADCAST_TOPIC, ex.getMessage());
        }

        audit(currentUserId(), actorUsername, "EMERGENCY_BROADCAST",
            "Broadcast (" + severity + "): " + request.getMessage());
        return EmergencyActionResponseDTO.builder()
            .action("BROADCAST")
            .takenAt(Instant.now())
            .actorUsername(actorUsername)
            .affectedRows(1)
            .message("Broadcast queued on " + BROADCAST_TOPIC + ".")
            .build();
    }

    private UUID currentUserId() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            return null;
        }
        return Optional.ofNullable(userRepository.findByUsername(username).orElse(null))
            .map(User::getId)
            .orElse(null);
    }

    /**
     * PR #228 review — emergency endpoints now require an MFA step-up
     * (X-Mfa-Token header) before they take effect, mirroring the same
     * pattern MVP-2 tenant lifecycle and MVP-4 impersonation start use.
     * Behaviour:
     *   - Actor enrolled in MFA → token must verify; missing / invalid
     *     token rejects with UnauthorizedException.
     *   - Actor not enrolled, strict mode on → reject (must enrol first).
     *   - Actor not enrolled, strict mode off → allow but emit a
     *     SECURITY_ALERT_TRIGGERED audit row recording the bypass, so the
     *     forensic trail survives.
     */
    private void verifyMfaStepUp(UUID actorId, String mfaToken, String action) {
        if (actorId == null) {
            throw new UnauthorizedException("emergency_unauth: missing super-admin actor for " + action);
        }
        boolean enrolled;
        try {
            enrolled = mfaService.isMfaEnabled(actorId);
        } catch (RuntimeException ex) {
            log.warn("[EMERGENCY] MFA enrollment lookup failed for actor {}: {}", actorId, ex.getMessage());
            throw new UnauthorizedException("mfa_required: enrollment lookup unavailable");
        }
        if (enrolled) {
            if (mfaToken == null || mfaToken.isBlank() || !mfaService.verifyCode(actorId, mfaToken)) {
                throw new UnauthorizedException(
                    "mfa_required: invalid or missing X-Mfa-Token for emergency " + action);
            }
            return;
        }
        if (requireMfaStrict) {
            throw new UnauthorizedException(
                "mfa_required: actor must enrol MFA before triggering emergency " + action);
        }
        // Unenrolled actor — let it through but audit the bypass.
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .userName(SecurityUtils.getCurrentUsername())
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .eventDescription("Emergency " + action + " performed without MFA step-up "
                    + "(actor not enrolled, non-strict mode).")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[EMERGENCY] Failed to audit MFA-bypass event", ex);
        }
    }

    private void audit(UUID userId, String username, String descriptionPrefix, String description) {
        try {
            AuditEventRequestDTO dto = AuditEventRequestDTO.builder()
                .userId(userId)
                .userName(username)
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .eventDescription(descriptionPrefix + " — " + description)
                .status(AuditStatus.SUCCESS)
                .build();
            auditEventLogService.logEvent(dto);
        } catch (RuntimeException ex) {
            log.warn("[EMERGENCY] Audit emit failed for {}: {}", descriptionPrefix, ex.getMessage());
        }
    }
}
