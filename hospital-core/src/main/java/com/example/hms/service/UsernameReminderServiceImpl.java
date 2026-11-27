package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsernameReminderServiceImpl implements UsernameReminderService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final SmsService smsService;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public void sendReminder(String identifier, Locale locale, String requestIp) {
        // Always behave the same to prevent account enumeration
        Optional<User> userOpt = resolve(identifier)
            // Only to a contact its holder confirmed: an account not yet activated gets nothing.
            .filter(User::isActive)
            // One delivered reminder per account per cooldown, whoever asks.
            .filter(user -> !cooldown.recentlySent(user.getId()));

        // D2000 (account enumeration): whatever happens below, nothing escapes
        // this method. A missing address, a refused provider or an absent mail
        // transport is logged by user id only; the caller's answer is the same
        // for an unknown identifier, a phone match and an email match.
        userOpt.ifPresent(user -> {
            boolean sent;
            if (identifier.contains("@")) {
                sent = sendByEmail(user, locale);
            } else if (smsService != null && smsService.deliversRealSms()) {
                // D2000 class: the logging mock is not a transport, so only a real one is used.
                // QA D1123 sibling: the text comes from the reader's bundle.
                sent = sendBySms(user, locale);
            } else {
                // No real SMS transport: still send by email when possible.
                log.info("Username reminder SMS not sent for userId={}: no SMS transport is configured",
                    user.getId());
                sent = sendByEmail(user, locale);
            }
            if (sent) {
                cooldown.sent(user.getId());
                log.info("📨 Username reminder issued for userId={} ip={}", user.getId(), requestIp);
            }
        });
    }

    /** Sent reminders, per account (this instance's memory). */
    private final ReminderCooldown cooldown = new ReminderCooldown();

    /**
     * The account an e-mail address or a phone number names. A phone number
     * is also tried in its international form, as the account stores it. Two
     * accounts under one identifier: nobody, as for an unknown one.
     */
    private Optional<User> resolve(String identifier) {
        try {
            Optional<User> typed = userRepository.findActiveByEmailOrPhone(identifier);
            if (typed != null && typed.isPresent()) {
                return typed;
            }
            if (identifier.contains("@")) {
                return Optional.empty();
            }
            String e164 = com.example.hms.utility.PhoneNumbers.toE164(
                identifier, com.example.hms.utility.PhoneNumbers.DEFAULT_CALLING_CODE);
            if (e164 == null || e164.equals(identifier)) {
                return Optional.empty();
            }
            Optional<User> international = userRepository.findActiveByEmailOrPhone(e164);
            return international == null ? Optional.empty() : international;
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException ambiguous) {
            log.warn("Username reminder not sent: the identifier matches more than one account");
            return Optional.empty();
        }
    }

    /** At most one reminder per account every {@link #WINDOW_MS}. */
    static final class ReminderCooldown {
        static final long WINDOW_MS = 10 * 60 * 1000L;
        private static final int MAX_TRACKED = 10_000;
        private final java.util.Map<java.util.UUID, Long> lastSent = new java.util.concurrent.ConcurrentHashMap<>();

        boolean recentlySent(java.util.UUID accountId) {
            Long previous = lastSent.get(accountId);
            return previous != null && System.currentTimeMillis() - previous < WINDOW_MS;
        }

        void sent(java.util.UUID accountId) {
            long now = System.currentTimeMillis();
            if (lastSent.size() > MAX_TRACKED) {
                lastSent.entrySet().removeIf(entry -> now - entry.getValue() >= WINDOW_MS);
            }
            lastSent.put(accountId, now);
        }
    }

    private boolean sendByEmail(User user, Locale locale) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.info("Username reminder not sent for userId={}: no email address on the account", user.getId());
            return false;
        }
        try {
            emailService.sendUsernameReminderEmail(user.getEmail(), user.getUsername(), locale);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Username reminder email not sent for userId={}: {}", user.getId(),
                ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean sendBySms(User user, Locale locale) {
        try {
            smsService.send(user.getPhoneNumber(), messageSource.getMessage(
                "sms.username.reminder", new Object[] {user.getUsername()},
                "sms.username.reminder", locale));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Username reminder SMS not sent for userId={}: {}", user.getId(),
                ex.getClass().getSimpleName());
            return false;
        }
    }
}
