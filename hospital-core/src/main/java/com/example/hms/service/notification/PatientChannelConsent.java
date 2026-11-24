package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

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

    /** Values of the choice that made the decision, never a mutable persistence entity. */
    public record Decision(boolean allowed, UUID userId, UUID recordedBy, LocalDateTime recordedAt) {
        public static Decision allow(UUID userId) { return new Decision(true, userId, null, null); }

        /** A foreign or missing registration cannot disclose a recorder's identity. */
        public Decision scopeTo(UUID accountId) {
            return userId != null && userId.equals(accountId) ? this : new Decision(allowed, null, null, null);
        }
    }

    /** One read captures both the decision and the original refusal's actor/date. */
    public Decision decision(UUID userId, NotificationType type, NotificationChannel channel) {
        if (userId == null) return Decision.allow(null);
        List<NotificationPreference> preferences = preferenceRepository.findByUser_IdAndNotificationType(userId, type);
        return preferences.stream()
            .filter(preference -> preference.getChannel() == channel)
            .map(preference -> preference.isEnabled() ? Decision.allow(userId)
                : new Decision(false, userId, preference.getRecordedByUserId(), preference.getRecordedAt()))
            .findFirst()
            .orElseGet(() -> Decision.allow(userId));
    }

    /** Call inside a transaction. A null user (no portal account) is allowed. */
    public boolean allows(UUID userId, NotificationType type, NotificationChannel channel) {
        return decision(userId, type, channel).allowed();
    }
}
