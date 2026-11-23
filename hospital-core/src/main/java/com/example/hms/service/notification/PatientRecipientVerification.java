package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.model.Patient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.Locale;

/** Fail closed for every external patient notice, including generic result-available notices. */
@Component
public class PatientRecipientVerification {
    public boolean allows(Patient patient, NotificationChannel channel, String recipient) {
        if (patient == null || !StringUtils.hasText(recipient)) return false;
        String proof = channel == NotificationChannel.SMS ? patient.getVerifiedSmsRecipient()
            : channel == NotificationChannel.EMAIL ? patient.getVerifiedEmailRecipient() : null;
        String current = channel == NotificationChannel.SMS ? patient.getPhoneNumberPrimary() : patient.getEmail();
        return StringUtils.hasText(proof) && normalize(proof, channel).equals(normalize(recipient, channel))
            && normalize(proof, channel).equals(normalize(current, channel));
    }

    static String normalize(String value, NotificationChannel channel) {
        if (value == null) return "";
        // Exact identity except presentation whitespace/case; never guess a phone's country.
        return channel == NotificationChannel.EMAIL ? value.strip().toLowerCase(Locale.ROOT)
            : value.replaceAll("[\\s()\\-]", "");
    }
}
