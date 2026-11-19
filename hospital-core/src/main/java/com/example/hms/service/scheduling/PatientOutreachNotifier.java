package com.example.hms.service.scheduling;

import java.util.Locale;
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationText;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.service.NotificationService;
import com.example.hms.service.notification.PatientMessageGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared patient-outreach dispatch for scheduling surfaces (P3 #22):
 * waitlist slot offers and recall notices. Same channel contract as
 * AppointmentReminderService — in-app to the portal account when one
 * exists, SMS only over a real transport ({@code deliversRealSms()};
 * the mock logs bodies and must never carry PHI), both honouring the
 * patient's stored APPOINTMENT_REMINDER preferences (absent row =
 * enabled). The SMS goes through {@link PatientMessageGate} (QA D2202
 * class), so a refused SMS leaves a SKIPPED / OPTED_OUT row staff can see.
 * Best-effort by design: outreach failure must never fail the desk action
 * that triggered it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientOutreachNotifier {

    /** Hard cap — IKODDI gives no per-segment feedback, so keep bodies short. */
    static final int MAX_SMS_LENGTH = 320;

    private final NotificationService notificationService;
    private final PatientMessageGate messageGate;
    private final MessageSource messageSource;

    /**
     * Push a message to the patient over every enabled channel. The bell
     * stores the key and renders it in the language the patient reads it in
     * (QA D665); the SMS is sent once, rendered in {@code smsLocale}.
     *
     * @param notice what is announced (WAITLIST_OFFER or RECALL_DUE), at which
     *               hospital, about which entry: the preference and the trace row
     * @return true when at least one channel dispatched
     */
    public boolean notifyPatient(Patient patient, NotificationText text, Locale smsLocale,
                                 PatientMessageGate.Notice notice) {
        if (patient == null || text == null || notice == null) {
            return false;
        }
        String message = text.render(messageSource, smsLocale);
        if (message == null || message.isBlank()) {
            return false;
        }
        String body = message.length() > MAX_SMS_LENGTH
            ? message.substring(0, MAX_SMS_LENGTH) : message;

        boolean dispatched = false;
        User portalUser = patient.getUser();
        if (portalUser != null && portalUser.getUsername() != null
            && messageGate.allowsInApp(patient, NotificationType.APPOINTMENT_REMINDER)) {
            try {
                notificationService.createNotification(
                    text.key(), text.args(), portalUser.getUsername(), "APPOINTMENT_REMINDER");
                dispatched = true;
            } catch (RuntimeException ex) {
                log.warn("In-app outreach failed for patient {}: {}", patient.getId(), ex.getMessage());
            }
        }
        if (sendSmsBestEffort(patient, notice, body)) {
            dispatched = true;
        }
        return dispatched;
    }

    private boolean sendSmsBestEffort(Patient patient, PatientMessageGate.Notice notice, String message) {
        try {
            return messageGate.smsNow(patient, notice, message);
        } catch (RuntimeException ex) {
            log.warn("SMS outreach failed for patient {}: {}", patient.getId(), ex.getClass().getSimpleName());
            return false;
        }
    }
}
