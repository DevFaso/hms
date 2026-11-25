package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;

/** A display hint only; never an address accepted by a sender or proof of ownership. */
public final class PatientContactMask {
    private static final String HIDDEN = "••••";
    private PatientContactMask() { }

    public static String of(String recipient, NotificationChannel channel) {
        if (recipient == null || recipient.isBlank()) return null;
        String value = recipient.strip();
        if (channel == NotificationChannel.SMS) {
            String digits = value.replaceAll("[^0-9]", "");
            return digits.length() < 8 ? HIDDEN : HIDDEN + " " + digits.substring(digits.length() - 4);
        }
        if (channel == NotificationChannel.EMAIL) {
            int at = value.lastIndexOf('@');
            if (at <= 0 || at >= value.length() - 1) return HIDDEN;
            String domain = value.substring(at + 1);
            int dot = domain.lastIndexOf('.');
            String suffix = dot > 0 && dot < domain.length() - 1 ? domain.substring(dot) : "";
            return value.substring(0, 1) + "•••@" + domain.substring(0, 1) + "•••" + suffix;
        }
        return HIDDEN;
    }
}
