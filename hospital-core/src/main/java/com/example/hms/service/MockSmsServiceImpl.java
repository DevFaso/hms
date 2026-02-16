package com.example.hms.service;

import com.example.hms.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@Slf4j
public class MockSmsServiceImpl implements SmsService {

    @Override
    public void send(String phoneNumber, String message) {
        // Simulate SMS sending (or later replace with Twilio, etc.)
        log.info("📲 Mock SMS sent to {}: {}", phoneNumber, message);
    }

    @Override
    public void sendUsernameReminderSms(String phoneNumber, String username, Locale locale) {
        // Simulate sending a username reminder SMS
        String message = String.format("Hello! Your username is: %s", username);
        send(phoneNumber, message);
        log.info("📲 Mock SMS sent to {} for username reminder in locale {}", phoneNumber, locale);

    }
}
