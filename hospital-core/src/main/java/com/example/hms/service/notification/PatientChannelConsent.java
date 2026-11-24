package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The patient's stored channel choice for one kind of notice, the rule every patient dispatch path
 * already applies (AppointmentReminderService, LabResultReleaseNotifier): an explicit disabled
 * row turns the channel off, no row means on. A patient with no portal account has no rows and
 * cannot opt out in the app; the desk records a phone-only patient's refusal by removing the number.
 */
@Component
@RequiredArgsConstructor
public class PatientChannelConsent {

    private final NotificationPreferenceRepository preferenceRepository;

    /** Provenance of the current explicit refusal, without exposing any recipient contact. */
    public java.util.Optional<NotificationPreference> refusal(UUID userId, NotificationType type,
                                                             NotificationChannel channel) {
        if (userId == null) return java.util.Optional.empty();
        return preferenceRepository.findByUser_IdAndNotificationType(userId, type).stream()
            .filter(row -> row.getChannel() == channel && !row.isEnabled()).findFirst();
    }

    /** Call inside a transaction. A null user (no portal account) is allowed. */
    public boolean allows(UUID userId, NotificationType type, NotificationChannel channel) {
        if (userId == null) {
            return true;
        }
        List<NotificationPreference> preferences = preferenceRepository.findByUser_IdAndNotificationType(userId, type);
        return preferences.stream()
            .filter(preference -> preference.getChannel() == channel)
            .map(NotificationPreference::isEnabled)
            .findFirst()
            .orElse(true);
    }
}
