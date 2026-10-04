package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.EmailChangeRequest;
import com.example.hms.model.EmailChangeSend;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.EmailChangeSendRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.EmailAddresses;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import com.example.hms.utility.UserDisplayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * A user changing their OWN email address: {@code POST /auth/me/change-email}
 * and {@code POST /auth/me/change-email/confirm}.
 *
 * <p>The email is where a password reset is sent, so it changes in two steps:
 * <ol>
 *   <li><b>Request</b>, with the current password. The new address is
 *       normalised as registration does it and must pass the same rule the
 *       mail sender applies ({@link EmailAddresses}). A 6-digit code is sent
 *       to it after commit, and the change waits on the user's
 *       {@link EmailChangeRequest} row. {@code users.email} is untouched: the
 *       old address stays in force.</li>
 *   <li><b>Confirm</b>, with that code. Only then is the address applied, and
 *       the OLD address is told after commit, with the new one masked.</li>
 * </ol>
 *
 * <p><b>An address that already has an account is not revealed.</b> The
 * request is answered exactly as for a free address (same message, same
 * delivery report), but instead of a code the holder of that address gets a
 * notice that another account asked to use it, and the pending change carries
 * a code nobody was sent, so it can never be confirmed. Sending the holder a
 * notice, rather than sending nothing, keeps the delivery report truthful and
 * identical: a mail went to that address either way. The attempt is audited.
 *
 * <p><b>Limits.</b> A wrong password counts on this row, never the login
 * throttle: {@value #MAX_PASSWORD_FAILURES} within {@value #PASSWORD_LOCK_MINUTES}
 * minutes lock THIS endpoint for {@value #PASSWORD_LOCK_MINUTES} minutes, so a
 * stolen session cannot lock the owner out of signing in. Requests that pass
 * the password are limited too, so the endpoint cannot be used to mail an
 * inbox in a loop: {@value #MAX_REQUESTS_PER_USER} per user, and
 * {@value #MAX_SENDS_PER_ADDRESS} mails per address by any accounts, each per
 * {@value #WINDOW_MINUTES} minutes. The address count is of mails actually
 * sent ({@link EmailChangeSend}, keyed by a hash of the address), not of
 * pending changes, so an account that re-targets or drops its change still
 * counts. The code for a confirmed change also ends every unconsumed
 * password-reset link: one mailed to the old address must not outlive the
 * move.
 *
 * <p>The code follows the recovery-contact verification: 6 digits from a
 * {@link SecureRandom}, stored as a password-encoder hash, dead after
 * {@value #CODE_EXPIRY_MINUTES} minutes or {@value #MAX_CODE_ATTEMPTS} wrong
 * tries. Delivery is reported through
 * {@link ActivationDeliveryTracker#sendEmailAndReport}, the helper the
 * welcome mail uses too, so a deployment without a mail transport says
 * NOT_CONFIGURED.
 *
 * <p>Every refusal writes one {@code USER_UPDATE} FAILURE row carrying the
 * account id only: never an address, a password or a code. Nothing here logs
 * an address. Refusals are {@link BusinessException}s and both steps commit
 * on them ({@code noRollbackFor}), so the counters they advance survive. The
 * writes that can meet a unique index run in {@link EmailChangeWrites}, in
 * their own transactions, so a race answers 400, never 500.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnEmailChangeService {

    static final int CODE_EXPIRY_MINUTES = 15;
    static final int MAX_CODE_ATTEMPTS = 5;
    static final int MAX_PASSWORD_FAILURES = 5;
    static final int PASSWORD_LOCK_MINUTES = 15;
    static final int WINDOW_MINUTES = 60;
    static final int MAX_REQUESTS_PER_USER = 5;
    static final int MAX_SENDS_PER_ADDRESS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository requestRepository;
    private final EmailChangeSendRepository sendRepository;
    private final EmailChangeWrites writes;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuditEventLogService auditEventLogService;

    /**
     * Step 1: check the current password, the limits and the address, then
     * mail the new address. The account's email does not change here.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void requestChange(UUID userId, String currentPassword, String newEmail) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
        EmailChangeRequest state = lockedRowOf(userId);
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

        boolean withinLimit = countRequest(state, now);
        requestRepository.save(state);
        if (!withinLimit) {
            throw refused(userId, "too many requests from this account", "user.email.change.ratelimited");
        }

        String email = EmailAddresses.normalize(newEmail);
        if (email == null || email.length() > EmailAddresses.MAX_LENGTH || !EmailAddresses.isDeliverable(email)) {
            throw refused(userId, "the new address is not a valid email address", "user.update.email.invalid");
        }
        if (email.equalsIgnoreCase(user.getEmail())) {
            throw refused(userId, "the new address is the current one", "user.email.change.same");
        }
        String addressHash = EmailAddresses.hash(email);
        LocalDateTime windowStart = now.minusMinutes(WINDOW_MINUTES);
        if (sendRepository.countByAddressHashAndSentAtAfter(addressHash, windowStart) >= MAX_SENDS_PER_ADDRESS) {
            throw refused(userId, "too many recent mails to this address", "user.email.change.ratelimited");
        }

        boolean taken = userRepository.existsEmailOnOtherAccount(email, userId);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        state.setPendingEmail(email);
        // A taken address gets a code nobody is sent: the change can never be
        // confirmed, yet the row looks, and counts, like any other request.
        state.setCodeHash(passwordEncoder.encode(taken ? code + ":" + UUID.randomUUID() : code));
        state.setCodeExpiresAt(now.plusMinutes(CODE_EXPIRY_MINUTES));
        state.setCodeAttempts(0);
        requestRepository.save(state);
        // Every mail to the address counts, a code or an in-use notice alike.
        sendRepository.deleteSentBefore(windowStart);
        sendRepository.save(EmailChangeSend.builder().addressHash(addressHash).sentAt(now).build());

        Locale locale = LocaleContextHolder.getLocale();
        Runnable send;
        if (taken) {
            audit(userId, "Own email change asked for an address that belongs to another account: "
                + "answered as a request, and the holder was notified instead", AuditStatus.FAILURE);
            send = () -> emailService.sendEmailAddressInUseNoticeEmail(email, locale);
        } else {
            audit(userId, "Own email change requested: a code was sent to the new address", AuditStatus.SUCCESS);
            send = () -> emailService.sendEmailChangeVerificationEmail(email, code, locale);
        }
        // After commit, as activation does: a rollback after the send would
        // mail a code that was never stored. One purpose either way, so the
        // report cannot tell a taken address from a free one.
        TransactionCallbacks.afterCommit(() -> ActivationDeliveryTracker.sendEmailAndReport(
            NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_CODE, email, send,
            emailService::deliversRealEmail));
    }

    /**
     * Step 2: the code sent to the new address. Applies the change and tells
     * the old address. A change that is gone (never requested, expired, or
     * cancelled by too many wrong codes) is a {@link PendingChangeGoneException},
     * so the client can drop its code form.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmChange(UUID userId, String code) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
        EmailChangeRequest state = requestRepository.findByUserId(userId).orElse(null);
        if (state == null || state.getPendingEmail() == null || state.getCodeHash() == null) {
            throw gone(userId, "no email change is waiting for a code", "user.email.change.nopending");
        }
        if (state.getCodeExpiresAt() == null || LocalDateTime.now().isAfter(state.getCodeExpiresAt())) {
            state.clearPendingChange();
            requestRepository.save(state);
            throw gone(userId, "the code has expired", "user.email.change.expired");
        }
        state.setCodeAttempts(state.getCodeAttempts() + 1);
        String typed = code == null ? "" : code.trim();
        if (typed.isEmpty() || !passwordEncoder.matches(typed, state.getCodeHash())) {
            if (state.getCodeAttempts() >= MAX_CODE_ATTEMPTS) {
                state.clearPendingChange();
                requestRepository.save(state);
                throw gone(userId, "too many wrong codes: the change was cancelled",
                    "user.email.change.code.exhausted");
            }
            requestRepository.save(state);
            throw refused(userId, "the code did not match", "user.email.change.code.invalid");
        }

        String newEmail = state.getPendingEmail();
        state.clearPendingChange();
        requestRepository.save(state);
        // Checked again: another account may have taken the address since the request.
        if (userRepository.existsEmailOnOtherAccount(newEmail, userId)) {
            throw refused(userId, "the new address belongs to another account", "user.update.email.taken");
        }
        try {
            writes.applyEmail(userId, newEmail);
        } catch (DataIntegrityViolationException raced) {
            // Taken between the check above and the write: the same answer.
            throw refused(userId, "the new address belongs to another account", "user.update.email.taken");
        }

        String oldEmail = user.getEmail();
        audit(userId, "Own email address changed", AuditStatus.SUCCESS);
        log.info("🔑 [CHANGE-EMAIL] Email address changed for user={}", userId);

        if (oldEmail != null && !oldEmail.isBlank()) {
            String displayName = UserDisplayUtil.resolveDisplayName(user);
            String masked = ActivationDeliveryTracker.maskEmail(newEmail);
            Locale locale = LocaleContextHolder.getLocale();
            TransactionCallbacks.afterCommit(() -> ActivationDeliveryTracker.sendEmailAndReport(
                NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_NOTICE, oldEmail,
                () -> emailService.sendEmailChangedNoticeEmail(oldEmail, displayName, masked, locale),
                emailService::deliversRealEmail));
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

    /** The pending change no longer exists (410); the client drops its code form. */
    public static class PendingChangeGoneException extends BusinessException {
        public PendingChangeGoneException(String message) {
            super(message);
        }
    }

    /**
     * The user's row, locked. The first request inserts it, in its own
     * transaction; when two first requests race, the loser's insert hits
     * {@code uq_email_change_user} and it reads the winner's row instead, so
     * neither answers 500 and a wrong password is still counted.
     */
    private EmailChangeRequest lockedRowOf(UUID userId) {
        return requestRepository.findByUserId(userId).orElseGet(() -> {
            try {
                writes.createRow(userId);
            } catch (DataIntegrityViolationException alreadyCreated) {
                log.debug("email change row for user={} was created by a concurrent request", userId);
            }
            return requestRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("email change row missing after insert"));
        });
    }

    private boolean passwordMatches(User user, String currentPassword) {
        return currentPassword != null && !currentPassword.isBlank()
            && user.getPasswordHash() != null
            && passwordEncoder.matches(currentPassword, user.getPasswordHash());
    }

    private void countPasswordFailure(EmailChangeRequest state, LocalDateTime now) {
        LocalDateTime windowStart = state.getPasswordWindowStartedAt();
        if (windowStart == null || windowStart.isBefore(now.minusMinutes(PASSWORD_LOCK_MINUTES))) {
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

    /** Count one request past the password; false when the user is over the limit. */
    private static boolean countRequest(EmailChangeRequest state, LocalDateTime now) {
        LocalDateTime windowStart = state.getRequestWindowStartedAt();
        if (windowStart == null || windowStart.isBefore(now.minusMinutes(WINDOW_MINUTES))) {
            state.setRequestWindowStartedAt(now);
            state.setRequestCount(0);
        }
        if (state.getRequestCount() >= MAX_REQUESTS_PER_USER) {
            return false;
        }
        state.setRequestCount(state.getRequestCount() + 1);
        return true;
    }

    /** One FAILURE row, the account id only, then the refusal to throw. */
    private BusinessException refused(UUID userId, String reason, String messageKey) {
        audit(userId, "Own email change refused: " + reason, AuditStatus.FAILURE);
        return new BusinessException(MessageUtil.resolve(messageKey));
    }

    private PendingChangeGoneException gone(UUID userId, String reason, String messageKey) {
        audit(userId, "Own email change refused: " + reason, AuditStatus.FAILURE);
        return new PendingChangeGoneException(MessageUtil.resolve(messageKey));
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
}
