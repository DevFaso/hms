package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    smsService.sendUsernameReminderSms(user.getPhoneNumber(), user.getUsername(), locale);
                } else {
                    // If no SMS service, still send by email when possible
                    emailService.sendUsernameReminderEmail(user.getEmail(), user.getUsername(), locale);
                }
            }
            log.info("📨 Username reminder issued for userId={} ip={}", user.getId(), requestIp);
        });

    }
}
