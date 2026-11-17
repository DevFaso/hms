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
        Optional<User> userOpt = userRepository.findActiveByEmailOrPhone(identifier);

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
                log.info("📨 Username reminder issued for userId={} ip={}", user.getId(), requestIp);
            }
        });
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
