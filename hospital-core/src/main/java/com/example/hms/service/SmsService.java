package com.example.hms.service;

import java.util.Locale;

public interface SmsService {
    void send(String phoneNumber, String message);

    void sendUsernameReminderSms(String phoneNumber, String username, Locale locale);
}
