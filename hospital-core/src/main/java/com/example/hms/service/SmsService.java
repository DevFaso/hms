package com.example.hms.service;

import java.util.Locale;

public interface SmsService {
    void send(String phoneNumber, String message);

    void sendUsernameReminderSms(String phoneNumber, String username, Locale locale);

    /**
     * True only when messages actually reach a phone. The mock implementation
     * logs message bodies instead of sending — callers MUST check this before
     * routing anything secret (one-time credentials) through {@link #send}.
     */
    default boolean deliversRealSms() {
        return false;
    }
}
