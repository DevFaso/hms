package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.EmailChangeRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import com.example.hms.utility.UserDisplayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A user changing their OWN email address: {@code POST /auth/me/change-email}
 * and {@code POST /auth/me/change-email/confirm}.
 *
 * <p>The email is where a password reset is sent, so it changes in two steps:
 * <ol>
 *   <li><b>Request</b>, with the current password. The new address is
 *       normalised as registration does it (trimmed, lower case) and must be
 *       free on every other account in any letter case. A 6-digit code is
 *       sent to it after commit, and the change waits on the user's
 *       {@link EmailChangeRequest} row. {@code users.email} is untouched: the
 *       old address stays in force.</li>
 *   <li><b>Confirm</b>, with that code. Only then is the address applied, and
 *       the OLD address is told after commit, with the new one masked.</li>
 * </ol>
 *
 * <p>The code follows the recovery-contact verification this codebase already
 * runs: 6 digits from a {@link SecureRandom}, stored as a password-encoder
 * hash, dead after {@value #CODE_EXPIRY_MINUTES} minutes or
 * {@value #MAX_CODE_ATTEMPTS} wrong tries. Delivery is reported the way
 * activation reports it: after commit, to {@link ActivationDeliveryTracker},
 * as SENT, FAILED or NOT_CONFIGURED, so a deployment without a mail transport
 * says so instead of looking like a success.
 *
 * <p>Wrong passwords have their own counter on the same row, keyed on the
 * user: {@value #MAX_PASSWORD_FAILURES} within {@value #PASSWORD_WINDOW_MINUTES}
 * minutes lock THIS endpoint for {@value #PASSWORD_LOCK_MINUTES} minutes. It is
 * deliberately not the login throttle: a stolen session must not be able to
 * lock the owner out of signing in.
 *
 * <p>Every refusal writes one {@code USER_UPDATE} FAILURE row carrying the
 * account id only: never an address, a password or a code. Refusals are
 * {@link BusinessException}s, and both steps commit on them
 * ({@code noRollbackFor}), so the counters they advance survive the refusal.
 * The only writes a refusal commits are to that row (a counter, a reset
 * counter once the password matched, or a spent code); {@code users} is
 * written only by a successful confirm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnEmailChangeService {

    static final int CODE_EXPIRY_MINUTES = 15;
    static final int MAX_CODE_ATTEMPTS = 5;
    static final int MAX_PASSWORD_FAILURES = 5;
    static final int PASSWORD_WINDOW_MINUTES = 15;
    static final int PASSWORD_LOCK_MINUTES = 15;

    /** The users.email column length. */
    private static final int MAX_EMAIL_LENGTH = 100;
    /** Deliberately loose: the code sent to the address is the real proof. */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository requestRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuditEventLogService auditEventLogService;

    /**
     * Step 1: check the current password and the address, then send a code to
     * the new address. The account's email does not change here.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void requestChange(UUID userId, String currentPassword, String newEmail) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        EmailChangeRequest state = stateOf(userId);
        LocalDateTime now = LocalDateTime.now();

        if (state.getPasswordLockedUntil() != null && now.isBefore(state.getPasswordLockedUntil())) {
            throw refused(userId, "the endpoint is locked after repeated wrong passwords", "user.email.change.locked");
        }
        if (!passwordMatches(user, currentPassword)) {
            countPasswordFailure(state, now);
            throw refused(userId, "the current password did not match", "user.email.change.password");
        }
        state.setPasswordFailures(0);
        state.setPasswordWindowStartedAt(null);
        state.setPasswordLockedUntil(null);
        requestRepository.save(state);

        String email = normalize(newEmail);
        if (email == null || email.length() > MAX_EMAIL_LENGTH || !EMAIL_SHAPE.matcher(email).matches()) {
            throw refused(userId, "the new address is not a valid email address", "user.update.email.invalid");
        }
        if (email.equalsIgnoreCase(user.getEmail())) {
            throw refused(userId, "the new address is the current one", "user.email.change.same");
        }
        if (userRepository.existsEmailOnOtherAccount(email, userId)) {
            throw refused(userId, "the new address belongs to another account", "user.update.email.taken");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        state.setPendingEmail(email);
        state.setCodeHash(passwordEncoder.encode(code));
        state.setCodeExpiresAt(now.plusMinutes(CODE_EXPIRY_MINUTES));
        state.setCodeAttempts(0);
        requestRepository.save(state);
        audit(userId, "Own email change requested: a code was sent to the new address", AuditStatus.SUCCESS);

        Locale locale = LocaleContextHolder.getLocale();
        // After commit, as activation does: a rollback after the send would
        // mail a code that was never stored.
        TransactionCallbacks.afterCommit(() -> deliver(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_CODE, email,
            () -> emailService.sendEmailChangeVerificationEmail(email, code, locale)));
    }

    /**
     * Step 2: the code sent to the new address. Applies the change and tells
     * the old address.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmChange(UUID userId, String code) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        EmailChangeRequest state = requestRepository.findByUserId(userId).orElse(null);
        if (state == null || state.getPendingEmail() == null || state.getCodeHash() == null) {
            throw refused(userId, "no email change is waiting for a code", "user.email.change.nopending");
        }
        if (state.getCodeExpiresAt() == null || LocalDateTime.now().isAfter(state.getCodeExpiresAt())) {
            state.clearPendingChange();
            requestRepository.save(state);
            throw refused(userId, "the code has expired", "user.email.change.expired");
        }
        state.setCodeAttempts(state.getCodeAttempts() + 1);
        String typed = code == null ? "" : code.trim();
        if (typed.isEmpty() || !passwordEncoder.matches(typed, state.getCodeHash())) {
            boolean exhausted = state.getCodeAttempts() >= MAX_CODE_ATTEMPTS;
            if (exhausted) {
                state.clearPendingChange();
            }
            requestRepository.save(state);
            throw exhausted
                ? refused(userId, "too many wrong codes: the change was cancelled", "user.email.change.code.exhausted")
                : refused(userId, "the code did not match", "user.email.change.code.invalid");
        }

        String newEmail = state.getPendingEmail();
        state.clearPendingChange();
        requestRepository.save(state);
        // Checked again: another account may have taken the address since the request.
        if (userRepository.existsEmailOnOtherAccount(newEmail, userId)) {
            throw refused(userId, "the new address belongs to another account", "user.update.email.taken");
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        userRepository.save(user);
        audit(userId, "Own email address changed", AuditStatus.SUCCESS);
        log.info("🔑 [CHANGE-EMAIL] Email address changed for user={}", userId);

        if (oldEmail != null && !oldEmail.isBlank()) {
            String displayName = UserDisplayUtil.resolveDisplayName(user);
            String masked = ActivationDeliveryTracker.maskEmail(newEmail);
            Locale locale = LocaleContextHolder.getLocale();
            TransactionCallbacks.afterCommit(() -> deliver(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_NOTICE, oldEmail,
                () -> emailService.sendEmailChangedNoticeEmail(oldEmail, displayName, masked, locale)));
        }
    }

    /**
     * A single sign-on session asked to change its email: refused, since
     * Keycloak owns the address on that path. Audited like every refusal.
     *
     * @param userId the HMS user id, when the token carries one; may be null
     */
    public BusinessException refuseSingleSignOn(UUID userId) {
        return refused(userId, "the session is single sign-on, whose email Keycloak manages",
            "user.email.change.external");
    }

    private EmailChangeRequest stateOf(UUID userId) {
        return requestRepository.findByUserId(userId)
            .orElseGet(() -> EmailChangeRequest.builder().userId(userId).build());
    }

    private boolean passwordMatches(User user, String currentPassword) {
        return currentPassword != null && !currentPassword.isBlank()
            && user.getPasswordHash() != null
            && passwordEncoder.matches(currentPassword, user.getPasswordHash());
    }

    private void countPasswordFailure(EmailChangeRequest state, LocalDateTime now) {
        LocalDateTime windowStart = state.getPasswordWindowStartedAt();
        if (windowStart == null || windowStart.isBefore(now.minus(Duration.ofMinutes(PASSWORD_WINDOW_MINUTES)))) {
            state.setPasswordWindowStartedAt(now);
            state.setPasswordFailures(1);
        } else {
            state.setPasswordFailures(state.getPasswordFailures() + 1);
        }
        if (state.getPasswordFailures() >= MAX_PASSWORD_FAILURES) {
            state.setPasswordLockedUntil(now.plusMinutes(PASSWORD_LOCK_MINUTES));
            state.setPasswordFailures(0);
            state.setPasswordWindowStartedAt(null);
        }
        requestRepository.save(state);
    }

    /** Trim and lower-case, as registration does ({@code UserMapper.normalizeEmail}). */
    static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** One FAILURE row, the account id only, then the refusal to throw. */
    private BusinessException refused(UUID userId, String reason, String messageKey) {
        audit(userId, "Own email change refused: " + reason, AuditStatus.FAILURE);
        return new BusinessException(MessageUtil.resolve(messageKey));
    }

    private void audit(UUID userId, String description, AuditStatus status) {
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
            .userId(userId)
            .eventType(AuditEventType.USER_UPDATE)
            .eventDescription(description)
            .resourceId(userId == null ? null : userId.toString())
            .entityType("USER")
            .status(status)
            .build());
    }

    /**
     * Send, and report the outcome the way activation does. The report names
     * the address masked; a transport error's message can embed the raw
     * address, so it stays in the log.
     */
    private void deliver(String purpose, String to, Runnable send) {
        String outcome;
        String detail = null;
        try {
            send.run();
            outcome = NotificationDeliveryStatusDTO.OUTCOME_SENT;
        } catch (RuntimeException ex) {
            log.warn("⚠️ Email-change mail ({}) not sent: {}", purpose, ex.getClass().getSimpleName());
            boolean configured = emailService.deliversRealEmail();
            outcome = configured
                ? NotificationDeliveryStatusDTO.OUTCOME_FAILED
                : NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED;
            detail = configured
                ? "send failed — transport error in server logs"
                : "mail transport not configured on this deployment";
        }
        ActivationDeliveryTracker.report(NotificationDeliveryStatusDTO.builder()
            .channel(NotificationDeliveryStatusDTO.CHANNEL_EMAIL)
            .purpose(purpose)
            .outcome(outcome)
            .target(ActivationDeliveryTracker.maskEmail(to))
            .detail(detail)
            .build());
    }
}
