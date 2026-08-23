package com.example.hms.service.scheduling;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared patient-outreach dispatch for scheduling surfaces (P3 #22):
 * waitlist slot offers and recall notices. Same channel contract as
 * AppointmentReminderService — in-app to the portal account when one
 * exists, SMS only over a real transport ({@code deliversRealSms()};
 * the mock logs bodies and must never carry PHI), both honouring the
 * patient's stored APPOINTMENT_REMINDER preferences (absent row =
 * enabled). Best-effort by design: outreach failure must never fail
 * the desk action that triggered it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientOutreachNotifier {

    /** Hard cap — IKODDI gives no per-segment feedback, so keep bodies short. */
    static final int MAX_SMS_LENGTH = 320;

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationService notificationService;
    private final SmsService smsService;

    /**
     * Push a message to the patient over every enabled channel.
     *
     * @return true when at least one channel dispatched
     */
    public boolean notifyPatient(Patient patient, String message) {
        if (patient == null || message == null || message.isBlank()) {
            return false;
        }
        String body = message.length() > MAX_SMS_LENGTH
            ? message.substring(0, MAX_SMS_LENGTH) : message;

        boolean dispatched = false;
        User portalUser = patient.getUser();
        if (portalUser != null && portalUser.getUsername() != null
            && channelEnabled(portalUser, NotificationChannel.IN_APP)) {
            try {
                notificationService.createNotification(
                    body, portalUser.getUsername(), "APPOINTMENT_REMINDER");
                dispatched = true;
            } catch (RuntimeException ex) {
                log.warn("In-app outreach failed for patient {}: {}", patient.getId(), ex.getMessage());
            }
        }
        if (sendSmsBestEffort(patient, portalUser, body)) {
            dispatched = true;
        }
        return dispatched;
    }

    private boolean sendSmsBestEffort(Patient patient, User portalUser, String message) {
        if (!smsService.deliversRealSms()) {
            return false;
        }
        if (portalUser != null && !channelEnabled(portalUser, NotificationChannel.SMS)) {
            return false;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return false;
        }
        try {
            smsService.send(phone, message);
            return true;
        } catch (RuntimeException ex) {
            log.warn("SMS outreach failed for patient {}: {}", patient.getId(), ex.getMessage());
            return false;
        }
    }

    /** Explicit disabled row turns the channel off; no row means enabled. */
    private boolean channelEnabled(User user, NotificationChannel channel) {
        List<NotificationPreference> preferences = preferenceRepository
            .findByUser_IdAndNotificationType(user.getId(), NotificationType.APPOINTMENT_REMINDER);
        return preferences.stream()
            .filter(preference -> preference.getChannel() == channel)
            .map(NotificationPreference::isEnabled)
            .findFirst()
            .orElse(true);
    }
}
