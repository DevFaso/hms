package com.example.hms.service.transfusion;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.emar.ControlledAdministrationWitness;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * QA D4141 (r81): the second bedside check of a transfusion is made by a second qualified person, who
 * confirms it themselves.
 *
 * <p>Two things the hang of a bag asks of the person named as its second checker, both here so the
 * picker and the hang cannot drift apart:
 * <ul>
 *   <li><b>who</b>: an active member of the bedside team of the hospital (a nurse, a midwife or a
 *       doctor there, {@link #holdsBedsideRole}), the people the picker lists;</li>
 *   <li><b>their own confirmation</b>, typed by them on the screen of the colleague who hangs the bag
 *       (the two stand at the same bed): the current code of their authenticator or one of their
 *       backup codes when they have one, else their own password. The same factors, lockout and
 *       replay protection as the witness of a controlled-drug movement
 *       ({@code ControlledWitnessConfirmation}).</li>
 * </ul>
 * The factor is checked and dropped: never stored, logged or audited. Each outcome is audited against
 * the checker (confirmed or refused, by which method, for whom).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BedsideVerifierConfirmation {

    /** The request property a refused confirmation names, so the dialog marks the field the checker types in. */
    public static final String FIELD_SECRET = "verifierSecret";

    static final String ACTION = "transfusion bedside second check";
    static final String AUDIT_ENTITY = "TransfusionBedsideVerifier";

    public static final String METHOD_TOTP = "TOTP";
    public static final String METHOD_PASSWORD = "PASSWORD";
    /** The colleague has neither factor here: they cannot confirm, and another checker is chosen. */
    public static final String METHOD_UNAVAILABLE = "UNAVAILABLE";

    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final AuditEventLogService auditEventLogService;

    /**
     * A member of the bedside team by the role of their staff record at the hospital: what the picker
     * lists and what the hang accepts.
     */
    public static boolean holdsBedsideRole(Staff staff) {
        if (staff == null || staff.getAssignment() == null || staff.getAssignment().getRole() == null
                || staff.getAssignment().getRole().getCode() == null) {
            return false;
        }
        return ControlledAdministrationWitness.WITNESS_ROLE_CODES
            .contains(staff.getAssignment().getRole().getCode().toUpperCase(Locale.ROOT));
    }

    /** How this colleague would confirm: what the dialog asks them for. */
    public String methodFor(User verifier) {
        if (verifier == null || verifier.getId() == null) {
            return METHOD_UNAVAILABLE;
        }
        if (mfaService.isMfaEnabled(verifier.getId())) {
            return METHOD_TOTP;
        }
        return holdsLocalPassword(verifier) ? METHOD_PASSWORD : METHOD_UNAVAILABLE;
    }

    /**
     * The checker's own confirmation of this hang. The caller has already checked that this colleague
     * is of the bedside team and is not the person hanging the bag.
     *
     * @param secret the checker's current authenticator code (or a backup code) when they have an
     *               authenticator, else their password
     * @return the method that confirmed (TOTP or PASSWORD), to store with the administration
     * @throws FieldBusinessException (400, on {@value #FIELD_SECRET}) when it is missing, wrong, locked
     *         out or cannot be given; audited
     */
    public String confirm(User verifier, UUID performerUserId, UUID hospitalId, String secret) {
        // Support acting as a clinician never has a colleague's secret typed for them.
        if (com.example.hms.security.context.ImpersonationContextHolder.isImpersonating()) {
            throw refused(verifier, performerUserId, hospitalId, null, "IMPERSONATION",
                "transfusion.verifier.impersonation");
        }
        String method = methodFor(verifier);
        if (METHOD_UNAVAILABLE.equals(method)) {
            throw refused(verifier, performerUserId, hospitalId, null, METHOD_UNAVAILABLE,
                "transfusion.verifier.unavailable");
        }
        if (!StringUtils.hasText(secret)) {
            throw refused(verifier, performerUserId, hospitalId, method, "REQUIRED", "transfusion.verifier.required");
        }
        if (METHOD_TOTP.equals(method)) {
            requireAuthenticatorCode(verifier, performerUserId, hospitalId, secret);
        } else {
            requirePassword(verifier, performerUserId, hospitalId, secret);
        }
        audit(verifier, performerUserId, hospitalId, method, AuditStatus.SUCCESS, null);
        return method;
    }

    private void requireAuthenticatorCode(User verifier, UUID performerUserId, UUID hospitalId, String code) {
        try {
            mfaService.requireStepUp(verifier.getId(), code.trim(), ACTION, true);
        } catch (MfaStepUpRefusedException ex) {
            String reason = ex.getReason().name();
            throw switch (ex.getReason()) {
                case LOCKED -> refused(verifier, performerUserId, hospitalId, METHOD_TOTP, reason,
                    "transfusion.verifier.locked");
                case REUSED -> refused(verifier, performerUserId, hospitalId, METHOD_TOTP, reason,
                    "transfusion.verifier.reused");
                case NOT_ENROLLED, UNAVAILABLE, UNIDENTIFIED -> refused(verifier, performerUserId, hospitalId,
                    METHOD_TOTP, reason, "transfusion.verifier.unavailable");
                default -> refused(verifier, performerUserId, hospitalId, METHOD_TOTP, reason,
                    "transfusion.verifier.invalid");
            };
        }
    }

    private void requirePassword(User verifier, UUID performerUserId, UUID hospitalId, String password) {
        // Counted in the CHECKER's sign-in bucket before it is compared, as every password check is (D2001).
        LoginAttemptService.PasswordCheck check = loginAttemptService.checkPassword(verifier.getId(),
            () -> passwordEncoder.matches(password, verifier.getPasswordHash()));
        if (check == LoginAttemptService.PasswordCheck.LOCKED) {
            throw refused(verifier, performerUserId, hospitalId, METHOD_PASSWORD, "PASSWORD_LOCKED",
                "transfusion.verifier.locked");
        }
        if (check != LoginAttemptService.PasswordCheck.ACCEPTED) {
            throw refused(verifier, performerUserId, hospitalId, METHOD_PASSWORD, "PASSWORD_INVALID",
                "transfusion.verifier.invalid");
        }
    }

    private static boolean holdsLocalPassword(User verifier) {
        String source = verifier.getAuthSource() == null
            ? "internal" : verifier.getAuthSource().trim().toLowerCase(Locale.ROOT);
        return "internal".equals(source) && StringUtils.hasText(verifier.getPasswordHash());
    }

    private FieldBusinessException refused(User verifier, UUID performerUserId, UUID hospitalId, String method,
                                           String reasonCode, String messageKey) {
        audit(verifier, performerUserId, hospitalId, method, AuditStatus.FAILURE, reasonCode);
        return new FieldBusinessException(FIELD_SECRET, messageKey);
    }

    /** The outcome, against the checker: the method and who they confirmed for; never the secret. */
    private void audit(User verifier, UUID performerUserId, UUID hospitalId, String method, AuditStatus status,
                       String reasonCode) {
        try {
            String description = status == AuditStatus.SUCCESS
                ? "Transfusion bedside second check confirmed by " + method
                : "Transfusion bedside second check confirmation refused (" + reasonCode + ")";
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("performedBy", performerUserId == null ? null : performerUserId.toString());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(verifier.getId())
                .hospitalId(hospitalId)
                .eventType(status == AuditStatus.SUCCESS ? AuditEventType.MFA_VERIFIED : AuditEventType.MFA_FAILURE)
                .status(status)
                .eventDescription(description)
                .entityType(AUDIT_ENTITY)
                .resourceId(verifier.getId().toString())
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.error("[BEDSIDE-CHECK] Failed to audit a second-check confirmation for checker={}", verifier.getId(), ex);
        }
    }
}
