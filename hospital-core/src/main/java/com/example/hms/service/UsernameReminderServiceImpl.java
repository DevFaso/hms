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

        userOpt.ifPresent(user -> {
            // Prefer email if present; fall back to SMS if identifier was a phone
            if (identifier.contains("@")) {
                emailService.sendUsernameReminderEmail(user.getEmail(), user.getUsername(), locale);
            } else {
                if (smsService != null) {
                    // QA D1123 sibling: the reminder was the English literal
                    // "Hello! Your username is: %s" whatever the reader's language.
                    smsService.send(user.getPhoneNumber(), messageSource.getMessage(
                        "sms.username.reminder", new Object[] {user.getUsername()},
                        "sms.username.reminder", locale));
                } else {
                    // If no SMS service, still send by email when possible
                    emailService.sendUsernameReminderEmail(user.getEmail(), user.getUsername(), locale);
                }
            }
            log.info("📨 Username reminder issued for userId={} ip={}", user.getId(), requestIp);
        });

    }
}
