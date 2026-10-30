package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.LoginAttemptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Round 18: step-up re-authentication when a prescriber SIGNS a
 * controlled-drug prescription, the e-prescribing norm (US DEA EPCS requires
 * two-factor authentication at signing). Checked on that sign call only and
 * valid for that one signature: nothing is remembered on the session.
 *
 * <ul>
 *   <li><strong>Authenticator enrolled</strong> (an active TOTP in HMS): a
 *       current code from it, or an unused backup code, through the shared
 *       {@link MfaService#requireStepUp} - the same lockout (5 wrong codes, 15
 *       minutes), the same replay protection (a code already accepted, by the
 *       sign-in or an earlier signature, is refused) and the same
 *       {@code MFA_FAILURE} audit as every other step-up.</li>
 *   <li><strong>No authenticator</strong>: the prescriber's HMS password,
 *       checked against the stored hash, with its own lockout (5 wrong
 *       passwords per 15 minutes, then 15 minutes locked).</li>
 *   <li><strong>An account whose password HMS does not hold</strong> (signed
 *       in through Keycloak OIDC or SAML, {@code authSource} not
 *       {@code internal}): the password path cannot verify anything, so the
 *       signature is refused until the prescriber enrols an authenticator in
 *       HMS, which works the same whichever path signed them in. This is why
 *       the TOTP path is preferred: it is the one check both the local and
 *       the OIDC sign-in can use.</li>
 * </ul>
 *
 * <p>Both outcomes are audited as {@code PRESCRIPTION_SIGN_REAUTHENTICATION}
 * (SUCCESS or FAILURE, the method and the prescription id, never the secret).
 * The audit writer runs in its own transaction, so a refusal is on record
 * even though the signature itself rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrescriberReauthenticationService {

    static final String ACTION = "controlled-drug prescription signing";
    static final int MAX_PASSWORD_FAILURES = 5;
    static final long PASSWORD_WINDOW_MS = 15 * 60 * 1000L;
    static final long PASSWORD_LOCK_MS = 15 * 60 * 1000L;
    static final String PASSWORD_KEY_PREFIX = "rx-sign-password:";

    public static final String METHOD_TOTP = "TOTP";
    public static final String METHOD_PASSWORD = "PASSWORD";

    private final MfaService mfaService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore attemptStore;
    private final AuditEventLogService auditEventLogService;

    /** The verified factor and when, written on the prescription as its two-factor proof. */
    public record Verified(String method, LocalDateTime at) {}

    /**
     * Verifies the prescriber's re-authentication for one signature.
     *
     * @throws MfaStepUpRefusedException (403, never 401: the session is fine)
     *         when it is missing, wrong, locked out, or cannot be checked
     */
    public Verified requireForControlledSigning(UUID userId, UUID prescriptionId, String password, String mfaCode) {
        if (userId == null) {
            throw refused(null, prescriptionId, null, MfaStepUpRefusedException.Reason.UNIDENTIFIED);
        }
        // Support acting as the prescriber never signs a controlled order in their name.
        if (com.example.hms.security.context.ImpersonationContextHolder.isImpersonating()) {
            throw refused(userId, prescriptionId, null, MfaStepUpRefusedException.Reason.IMPERSONATION);
        }
        if (mfaService.isMfaEnabled(userId)) {
            try {
                mfaService.requireStepUp(userId, mfaCode, ACTION, true);
            } catch (MfaStepUpRefusedException ex) {
                audit(userId, prescriptionId, METHOD_TOTP, AuditStatus.FAILURE, ex.getReason().code());
                throw ex;
            }
            audit(userId, prescriptionId, METHOD_TOTP, AuditStatus.SUCCESS, null);
            return new Verified(METHOD_TOTP, LocalDateTime.now());
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw refused(userId, prescriptionId, METHOD_PASSWORD, MfaStepUpRefusedException.Reason.UNIDENTIFIED);
        }
        String source = user.getAuthSource() == null ? "internal" : user.getAuthSource().trim().toLowerCase(Locale.ROOT);
        if (!"internal".equals(source) || !StringUtils.hasText(user.getPasswordHash())) {
            throw refused(userId, prescriptionId, METHOD_PASSWORD, MfaStepUpRefusedException.Reason.NOT_ENROLLED);
        }
        if (!StringUtils.hasText(password)) {
            throw refused(userId, prescriptionId, METHOD_PASSWORD, MfaStepUpRefusedException.Reason.REQUIRED);
        }
        String key = PASSWORD_KEY_PREFIX + userId;
        // The attempt is counted BEFORE the password is compared (atomically in
        // the store), so concurrent guesses cannot exceed the limit; a right
        // password clears the count.
        boolean allowed = attemptStore.remainingLockMs(key) <= 0
            && attemptStore.recordFailure(key, MAX_PASSWORD_FAILURES, PASSWORD_WINDOW_MS, PASSWORD_LOCK_MS)
                <= MAX_PASSWORD_FAILURES;
        if (!allowed) {
            throw refused(userId, prescriptionId, METHOD_PASSWORD, MfaStepUpRefusedException.Reason.LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw refused(userId, prescriptionId, METHOD_PASSWORD, MfaStepUpRefusedException.Reason.INVALID);
        }
        attemptStore.clear(key);
        audit(userId, prescriptionId, METHOD_PASSWORD, AuditStatus.SUCCESS, null);
        return new Verified(METHOD_PASSWORD, LocalDateTime.now());
    }

    private MfaStepUpRefusedException refused(UUID userId, UUID prescriptionId, String method,
                                              MfaStepUpRefusedException.Reason reason) {
        audit(userId, prescriptionId, method, AuditStatus.FAILURE, reason.code());
        return new MfaStepUpRefusedException(reason);
    }

    private void audit(UUID userId, UUID prescriptionId, String method, AuditStatus status, String reasonCode) {
        try {
            String outcome = status == AuditStatus.SUCCESS ? "verified" : "refused (" + reasonCode + ")";
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(userId)
                .eventType(AuditEventType.PRESCRIPTION_SIGN_REAUTHENTICATION)
                .status(status)
                .eventDescription("Re-authentication for " + ACTION + " " + outcome
                    + (method == null ? "" : " by " + method))
                .entityType("Prescription")
                .resourceId(prescriptionId == null ? null : prescriptionId.toString())
                .build());
        } catch (RuntimeException ex) {
            log.error("[RX-SIGN] Failed to audit a signing re-authentication for user={}", userId, ex);
        }
    }
}
