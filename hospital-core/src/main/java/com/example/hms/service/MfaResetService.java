package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * QA r64 (lost phone): the two ways out of a second factor that can no longer be used.
 *
 * <ul>
 *   <li><b>An administrator resets a colleague's enrolment</b> ({@link #resetByAdministrator}): the account must be
 *       one the caller administers ({@link UserAccountAccess#canAdminister}: a hospital admin only for an account whose
 *       every assignment is at a hospital they administer and which holds no admin role anywhere, so never a
 *       super-admin's nor another administrator's; the super-admin any account). The reason is mandatory and kept in
 *       the audit row. The administrator confirms with their own step-up when they have an authenticator (the same
 *       rule and switch as the super-admin's enrolment change). The user sets up a new authenticator at their next
 *       sign-in when their role requires one.</li>
 *   <li><b>The user removes their own</b> ({@link #removeOwn}): a full session and a current code or an unused backup
 *       code from the authenticator being removed (the replacement's guard).</li>
 * </ul>
 *
 * <p>Both delete the enrolment and the backup codes, as the emergency re-enrolment does, and write one audit row.
 * Neither is open to a support session impersonating the user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaResetService {

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 500;
    private static final String USER_NOT_FOUND = "user.notFound";

    private final UserRepository userRepository;
    private final UserMfaEnrollmentRepository enrollmentRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final UserAccountAccess accountAccess;
    private final MfaService mfaService;
    private final AuditEventLogService auditEventLogService;

    /** The same switch as the other step-ups (emergency controls, impersonation, enrolment change). */
    @Value("${hms.support-impersonation.require-mfa-strict:false}")
    private boolean requireMfaStrict;

    /**
     * Resets another person's two-step verification. Everything that can refuse is checked before the
     * administrator's step-up, which may spend a backup code.
     *
     * @throws ResourceNotFoundException       no such account, or one the caller may not even see
     * @throws ClientSafeAccessDeniedException an account the caller sees but does not administer
     * @throws ConflictException               the caller's own account (use {@link #removeOwn})
     * @throws FieldBusinessException          a missing or too short reason
     */
    @Transactional
    public void resetByAdministrator(UUID targetUserId, String reason, String stepUpCode) {
        // A support session acts as the administrator: it must not strip a colleague's second factor
        // (the same rule as the account's own sign-in details, ImpersonationLimits).
        if (com.example.hms.security.context.ImpersonationContextHolder.isImpersonating()) {
            throw new ClientSafeAccessDeniedException(
                MessageUtil.resolve(com.example.hms.security.context.ImpersonationLimits.ACCOUNT_CHANGE_REFUSED));
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < REASON_MIN || why.length() > REASON_MAX) {
            throw new FieldBusinessException("reason", "mfa.reset.reasonRequired", REASON_MIN, REASON_MAX);
        }
        User target = userRepository.findById(targetUserId)
            .filter(user -> !user.isDeleted())
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND, targetUserId));
        if (accountAccess.isSelf(target)) {
            throw new ConflictException(MessageUtil.resolve("mfa.reset.self"));
        }
        if (!accountAccess.canAdminister(target)) {
            audit(AuditEventType.MFA_RESET, AuditStatus.FAILURE, target,
                "Two-step verification reset refused: the caller may not administer this account");
            if (accountAccess.canView(target)) {
                throw new ClientSafeAccessDeniedException(MessageUtil.resolve("user.administer.notYours"));
            }
            throw new ResourceNotFoundException(USER_NOT_FOUND, targetUserId);
        }
        UUID actorId = accountAccess.currentUserId().orElse(null);
        if (!mfaService.requireStepUp(actorId, stepUpCode, "MFA enrolment change", requireMfaStrict)) {
            audit(AuditEventType.SECURITY_ALERT_TRIGGERED, AuditStatus.SUCCESS, target,
                "MFA enrolment change performed without MFA step-up (actor not enrolled, non-strict mode).");
        }
        clear(target.getId());
        audit(AuditEventType.MFA_RESET, AuditStatus.SUCCESS, target,
            "Two-step verification reset by an administrator: " + why);
        log.info("[MFA] Two-step verification of user={} reset by user={}", target.getId(), actorId);
    }

    /**
     * Removes the caller's own authenticator, after a current code or an unused backup code from it.
     *
     * @throws com.example.hms.exception.MfaStepUpRefusedException (403) a missing, wrong or reused code, a challenge
     *         session or a support session; audited
     * @throws ConflictException nothing to remove
     */
    @Transactional
    public void removeOwn(User user, Authentication caller, String stepUpCode) {
        if (!mfaService.isMfaEnabled(user.getId())) {
            throw new ConflictException(MessageUtil.resolve("mfa.remove.nothing"));
        }
        mfaService.requireStepUpToChangeAuthenticator(user.getId(), caller, stepUpCode);
        clear(user.getId());
        audit(AuditEventType.MFA_RESET, AuditStatus.SUCCESS, user, "Two-step verification removed by the user");
    }

    private void clear(UUID userId) {
        enrollmentRepository.deleteAll(enrollmentRepository.findByUserId(userId));
        backupCodeRepository.deleteAllByUserId(userId);
    }

    private void audit(AuditEventType type, AuditStatus status, User target, String description) {
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
            .userId(accountAccess.currentUserId().orElse(null))
            .eventType(type)
            .status(status)
            .entityType("USER")
            .resourceId(target.getId().toString())
            .eventDescription(description)
            .build());
    }
}
