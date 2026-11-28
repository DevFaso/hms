package com.example.hms.service.billing.claim;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.ImpersonationLimits;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.HospitalCalendar;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Insurer claims plan PRs 2 to 4 (plan section 4.4, owner decision of 2026-11-26): who may do what on a bordereau,
 * always at the acting hospital (pinned, never the global view), plus what every claim service shares: the
 * hospital's own day, whole-franc amounts, free text without a card number, and the audit row written after commit.
 *
 * <ul>
 *   <li><b>Readers</b>: hospital admin, accountant, billing specialist, claims reviewer; the verified platform
 *       super-admin pinned to the hospital reads too.</li>
 *   <li><b>Builders</b>: the same four roles build, export and submit a bordereau, record the payer's refusal of a
 *       line and ask for a write-off. Never the super-admin.</li>
 *   <li><b>Finance</b>: hospital admin and accountant only record, allocate and reverse a payer's remittance,
 *       cancel a submitted bordereau, re-bill a refused amount to the patient and decide a write-off.</li>
 * </ul>
 *
 * Every write is refused to a support session impersonating a user: the four-eyes steps would be one person with
 * two accounts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsurerClaimAccess {

    /** The audit rows' entity: a bordereau and everything settled on it. */
    static final String AUDIT_ENTITY = "INSURER_CLAIM_BATCH";
    static final int TEXT_MAX = 500;

    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** Who acts, and at which hospital. */
    public record Actor(UUID userId, UUID hospitalId) {
    }

    /** A reader of the claims of the acting hospital; the hospital it reads. */
    public UUID requireReader() {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        boolean reader = actingScopeResolver.isVerifiedSuperAdmin()
            || (userId != null && roleValidator.canDecidePayerShare(userId, hospitalId));
        if (!reader) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.claim.readersOnly"));
        }
        return hospitalId;
    }

    /** A builder of bordereaux at the acting hospital (never a support session, never the super-admin alone). */
    public Actor requireBuilder() {
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "insurer claim");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canDecidePayerShare(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.claim.buildersOnly"));
        }
        return new Actor(userId, hospitalId);
    }

    /** The hospital admin or the accountant of the acting hospital (never a support session). */
    public Actor requireFinance() {
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "insurer payment");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !isFinance(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.claim.financeOnly"));
        }
        return new Actor(userId, hospitalId);
    }

    /** Whether the person holds the hospital admin or accountant role at the hospital. */
    public boolean isFinance(UUID userId, UUID hospitalId) {
        return userId != null && roleValidator.canSettleInsurerClaims(userId, hospitalId);
    }

    /** The current user, for the "asked by me" flags of a read; null for none. */
    public UUID currentUserId() {
        return roleValidator.getCurrentUserId();
    }

    /** Today at the hospital (its own time zone, else the server's). */
    public LocalDate today(UUID hospitalId) {
        String zone = hospitalRepository.findTimezoneIdById(hospitalId).orElse(null);
        return LocalDate.now(clock.withZone(HospitalCalendar.zoneOf(zone, clock)));
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Whole francs, more than 0; refused under {@code field} otherwise. */
    public static BigDecimal requireWholePositive(BigDecimal amount, String field) {
        if (amount == null) {
            throw new FieldBusinessException(field, "billing.claim.amountRequired");
        }
        if (amount.signum() <= 0) {
            throw new FieldBusinessException(field, "billing.claim.amountPositive");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(field, "billing.payment.wholeAmountOnly");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** A text a person typed, trimmed: required, at most {@code max} characters, never a card number. */
    public static String requireText(String raw, String field, int max, String requiredKey) {
        String text = trimToNull(raw);
        if (text == null) {
            throw new FieldBusinessException(field, requiredKey);
        }
        return checkedText(text, field, max);
    }

    /** As {@link #requireText}, for a text that may be left out (null then). */
    public static String optionalText(String raw, String field, int max) {
        String text = trimToNull(raw);
        return text == null ? null : checkedText(text, field, max);
    }

    private static String checkedText(String text, String field, int max) {
        if (text.length() > max) {
            throw new FieldBusinessException(field, "billing.claim.textTooLong", max);
        }
        CardNumbers.requireNone(text, field);
        return text;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Whole francs with the reader's grouping (plain spaces), as the billing refusals print them. */
    public static String francs(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(LocaleContextHolder.getLocale());
        format.setGroupingUsed(true);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP).longValueExact())
            .replace(' ', ' ').replace(' ', ' ');
    }

    /** The display names of these users (first name, last name); a user with no name is left out. */
    public Map<UUID, String> names(Collection<UUID> userIds) {
        Set<UUID> ids = new HashSet<>();
        for (UUID id : userIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(ids)) {
            String first = user.getFirstName() != null ? user.getFirstName() : "";
            String last = user.getLastName() != null ? user.getLastName() : "";
            String joined = (first + " " + last).trim();
            if (!joined.isEmpty()) {
                names.put(user.getId(), joined);
            }
        }
        return names;
    }

    /**
     * One audit row after commit, anchored on the hospital: ids only (no patient, no policy number, no reason text,
     * no amount tied to a person). {@code what} is a fixed English phrase the portal translates; {@code subject}
     * names the kind of record ("batch", "payment", "line", "write-off") and {@code subjectId} its id.
     */
    public void audit(AuditEventType type, Actor actor, UUID batchId, String what, String subject, UUID subjectId) {
        if (auditEventLogService == null) {
            return;
        }
        String resource = Objects.toString(batchId, null);
        String description = what + ": " + subject + " " + subjectId;
        UUID hospitalId = actor.hospitalId();
        UUID userId = actor.userId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(resource)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Insurer claim audit {} not written for {} {}: {}", type, subject, subjectId,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
