package com.example.hms.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The {@link SmsService} of a deployment with no SMS provider configured. It
 * sends nothing, and says so: {@link #deliversRealSms()} stays false, so a
 * caller can refuse or fall back instead of telling a user a text is on its way.
 *
 * <p>D2000 class: it used to log "Mock SMS sent to &lt;full number&gt;: &lt;body&gt;",
 * which put phone numbers, verification codes and patient text in the log
 * and read like a delivery. It now logs a masked number and the body length only.
 */
@Service
@Slf4j
public class MockSmsServiceImpl implements SmsService {

    @Override
    public void send(String phoneNumber, String message) {
        log.info("SMS NOT sent (no SMS provider configured) to {} ({} characters)",
            maskPhone(phoneNumber), message == null ? 0 : message.length());
    }

    /** Display-safe form for logs: a leading '+' and the last two digits. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "••••";
        }
        boolean plus = phone.startsWith("+");
        return (plus ? "+" : "") + "•".repeat(phone.length() - 2 - (plus ? 1 : 0))
            + phone.substring(phone.length() - 2);
    }
}
