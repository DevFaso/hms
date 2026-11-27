package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.StockWitnessConfirmation;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.pharmacy.StockWitnessConfirmationRepository;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * QA D2679 (r65 → r77): the witness of a controlled-drug movement was a name picked in a list. The person
 * recording the movement chose a colleague and the movement was final: the witness did nothing, and need not
 * even have been in the room. A second-person check that the second person never makes is not a check.
 *
 * <p>The witness now confirms <b>themselves</b>, with their own factor, on the performer's screen (the two are
 * standing at the same cupboard), and no controlled movement is recorded without it:
 * <ul>
 *   <li><strong>authenticator enrolled</strong>: a current code from the WITNESS's authenticator, or one of
 *       their unused backup codes, through the shared {@link MfaService#requireStepUp} (same lockout, same
 *       replay protection, same {@code MFA_FAILURE} audit as every step-up);</li>
 *   <li><strong>no authenticator</strong>: the witness's own password, counted in the witness's sign-in bucket
 *       ({@link LoginAttemptService#checkPassword}), so guesses add up with their sign-in and lock the same
 *       way;</li>
 *   <li><strong>neither</strong> (an account this application holds no password for, with no authenticator):
 *       the witness cannot confirm, and another witness has to be chosen.</li>
 * </ul>
 *
 * <p>A confirmation is one row (V388): this witness, for this performer, at this hospital, good for
 * {@link #VALIDITY}. It is short because it stands for "I am here, watching this": long enough for the parts of
 * one fill (a fill that draws on two lots is two movements) or the lots of one delivery, too short to be reused
 * later. {@link ControlledStockWitness#resolve} asks for it before any caller writes a movement, so a movement
 * naming a witness who has not confirmed is refused whole. The secret is checked and dropped: never stored,
 * logged or audited. Each outcome is audited against the witness (confirmed or refused, by which method, for
 * whom).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlledWitnessConfirmation {

    /** How long a confirmation stands for the witness's presence. */
    public static final Duration VALIDITY = Duration.ofMinutes(5);

    /** The request property a refused confirmation names, so the form marks the field the witness types in. */
    static final String FIELD_SECRET = "secret";

    static final String ACTION = "controlled-drug movement witness confirmation";
    static final String AUDIT_ENTITY = "ControlledStockWitness";

    public static final String METHOD_TOTP = "TOTP";
    public static final String METHOD_PASSWORD = "PASSWORD";
    /** The witness has neither factor here: they cannot confirm. */
    public static final String METHOD_UNAVAILABLE = "UNAVAILABLE";

    private final StockWitnessConfirmationRepository confirmationRepository;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final AuditEventLogService auditEventLogService;

    /** How this colleague would confirm: what the picker asks them for. */
    public String methodFor(User witness) {
        if (witness == null || witness.getId() == null) {
            return METHOD_UNAVAILABLE;
        }
        if (mfaService.isMfaEnabled(witness.getId())) {
            return METHOD_TOTP;
        }
        return holdsLocalPassword(witness) ? METHOD_PASSWORD : METHOD_UNAVAILABLE;
    }

    /** Has this witness confirmed for this performer at this hospital, and is the confirmation still good? */
    public boolean isConfirmed(UUID witnessId, UUID performerId, UUID hospitalId) {
        if (witnessId == null || performerId == null || hospitalId == null) {
            return false;
        }
        return confirmationRepository.existsByWitnessUserIdAndPerformedByUserIdAndHospitalIdAndExpiresAtAfter(
            witnessId, performerId, hospitalId, LocalDateTime.now());
    }

    /**
     * The witness's own confirmation, for the movements {@code performer} records at {@code hospitalId} in the
     * next {@link #VALIDITY}. The caller has already checked that this colleague may witness.
     *
     * @param secret the witness's current authenticator code (or a backup code) when they have an
     *               authenticator, else their password
     * @throws FieldBusinessException (400, on {@value #FIELD_SECRET}) when it is missing, wrong, locked out or
     *         cannot be given; audited
     */
    public StockWitnessConfirmation confirm(User witness, User performer, UUID hospitalId, String secret) {
        // Support acting as a pharmacist never has a colleague's secret typed for them.
        if (com.example.hms.security.context.ImpersonationContextHolder.isImpersonating()) {
            throw refused(witness, performer, hospitalId, null, "IMPERSONATION", "witnessConfirmation.impersonation");
        }
        String method = methodFor(witness);
        if (METHOD_UNAVAILABLE.equals(method)) {
            throw refused(witness, performer, hospitalId, null, METHOD_UNAVAILABLE, "witnessConfirmation.unavailable");
        }
        if (!StringUtils.hasText(secret)) {
            throw refused(witness, performer, hospitalId, method, "REQUIRED", "witnessConfirmation.required");
        }
        if (METHOD_TOTP.equals(method)) {
            requireAuthenticatorCode(witness, performer, hospitalId, secret);
        } else {
            requirePassword(witness, performer, hospitalId, secret);
        }
        LocalDateTime now = LocalDateTime.now();
        StockWitnessConfirmation saved = confirmationRepository.save(StockWitnessConfirmation.builder()
            .witnessUserId(witness.getId())
            .performedByUserId(performer.getId())
            .hospitalId(hospitalId)
            .method(method)
            .confirmedAt(now)
            .expiresAt(now.plus(VALIDITY))
            .build());
        audit(witness, performer, hospitalId, method, AuditStatus.SUCCESS, null);
        return saved;
    }

    private void requireAuthenticatorCode(User witness, User performer, UUID hospitalId, String code) {
        try {
            mfaService.requireStepUp(witness.getId(), code.trim(), ACTION, true);
        } catch (MfaStepUpRefusedException ex) {
            String reason = ex.getReason().name();
            throw switch (ex.getReason()) {
                case LOCKED -> refused(witness, performer, hospitalId, METHOD_TOTP, reason, "witnessConfirmation.locked");
                case REUSED -> refused(witness, performer, hospitalId, METHOD_TOTP, reason, "witnessConfirmation.reused");
                case NOT_ENROLLED, UNAVAILABLE, UNIDENTIFIED ->
                    refused(witness, performer, hospitalId, METHOD_TOTP, reason, "witnessConfirmation.unavailable");
                default -> refused(witness, performer, hospitalId, METHOD_TOTP, reason, "witnessConfirmation.invalid");
            };
        }
    }

    private void requirePassword(User witness, User performer, UUID hospitalId, String password) {
        // Counted in the WITNESS's sign-in bucket before it is compared, as every password check is (D2001).
        LoginAttemptService.PasswordCheck check = loginAttemptService.checkPassword(witness.getId(),
            () -> passwordEncoder.matches(password, witness.getPasswordHash()));
        if (check == LoginAttemptService.PasswordCheck.LOCKED) {
            throw refused(witness, performer, hospitalId, METHOD_PASSWORD, "PASSWORD_LOCKED", "witnessConfirmation.locked");
        }
        if (check != LoginAttemptService.PasswordCheck.ACCEPTED) {
            throw refused(witness, performer, hospitalId, METHOD_PASSWORD, "PASSWORD_INVALID", "witnessConfirmation.invalid");
        }
    }

    private static boolean holdsLocalPassword(User witness) {
        String source = witness.getAuthSource() == null ? "internal" : witness.getAuthSource().trim().toLowerCase(Locale.ROOT);
        return "internal".equals(source) && StringUtils.hasText(witness.getPasswordHash());
    }

    private FieldBusinessException refused(User witness, User performer, UUID hospitalId, String method,
                                           String reasonCode, String messageKey) {
        audit(witness, performer, hospitalId, method, AuditStatus.FAILURE, reasonCode);
        return new FieldBusinessException(FIELD_SECRET, messageKey);
    }

    /** The outcome, against the witness: the method and who they confirmed for; never the secret. */
    private void audit(User witness, User performer, UUID hospitalId, String method, AuditStatus status,
                       String reasonCode) {
        try {
            String description = status == AuditStatus.SUCCESS
                ? "Controlled-drug movement witness confirmed by " + method
                : "Controlled-drug movement witness confirmation refused (" + reasonCode + ")";
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("performedBy", performer == null || performer.getId() == null ? null : performer.getId().toString());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(witness.getId())
                .hospitalId(hospitalId)
                .eventType(status == AuditStatus.SUCCESS ? AuditEventType.MFA_VERIFIED : AuditEventType.MFA_FAILURE)
                .status(status)
                .eventDescription(description)
                .entityType(AUDIT_ENTITY)
                .resourceId(witness.getId().toString())
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.error("[WITNESS] Failed to audit a witness confirmation for witness={}", witness.getId(), ex);
        }
    }
}
