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
import com.example.hms.security.GlobalSessionRevocationService;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.EmergencyControlService;
import com.example.hms.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    @Transactional
    public EmergencyActionResponseDTO forceLogoutAll(EmergencyForceLogoutRequestDTO request) {
        UUID actorId = currentUserId();
        String actorUsername = SecurityUtils.getCurrentUsername();
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
    public EmergencyActionResponseDTO killFeature(EmergencyKillFeatureRequestDTO request) {
        String actorUsername = SecurityUtils.getCurrentUsername();
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
    public EmergencyActionResponseDTO forceMfaReenrol(EmergencyForceMfaRequestDTO request) {
        String actorUsername = SecurityUtils.getCurrentUsername();
        List<UUID> targets = request.getUserIds();
        if (targets == null || targets.isEmpty()) {
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

        audit(currentUserId(), actorUsername, "EMERGENCY_FORCE_MFA_REENROL",
            "Cleared MFA for " + targets.size() + " user(s): " + request.getReason());
        return EmergencyActionResponseDTO.builder()
            .action("FORCE_MFA_REENROL")
            .takenAt(Instant.now())
            .actorUsername(actorUsername)
            .affectedRows(affected)
            .message("Cleared " + affected + " MFA enrolment row(s) for " + targets.size() + " user(s).")
            .build();
    }

    @Override
    @Transactional
    public EmergencyActionResponseDTO broadcast(EmergencyBroadcastRequestDTO request) {
        String actorUsername = SecurityUtils.getCurrentUsername();
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
