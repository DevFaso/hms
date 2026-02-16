package com.example.hms.service;

import java.util.Locale;

public interface UsernameReminderService {
    void sendReminder(String identifier, Locale locale, String requestIp);

}
