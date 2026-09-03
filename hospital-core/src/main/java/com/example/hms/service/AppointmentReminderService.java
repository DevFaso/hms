package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.NotificationPreferenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.example.hms.service.i18n.PatientLocaleResolver;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Upcoming-appointment reminders (P1 #7). The reminder infrastructure was
 * enum-only before this: {@code NotificationType.APPOINTMENT_REMINDER} was
 * storable as a preference but nothing ever consulted it or sent anything.
 * <p>
 * The sweep finds appointments starting within the lead window that have no
 * {@code reminderSentAt} stamp and, per appointment: pushes an in-app
 * notification to the patient's portal account and sends an SMS over the
 * IKODDI channel ({@code deliversRealSms()} guard — the mock transport logs
 * bodies and must never carry PHI). Both channels honour the patient's
 * stored {@link NotificationPreference} rows for APPOINTMENT_REMINDER —
 * this is the first dispatch path that actually consults them; absent rows
 * default to enabled. Every processed appointment is stamped exactly once,
 * even when skipped, so the sweep converges and never double-texts
 * (CriticalValueNotificationService semantics).
 */
@Slf4j
@Service
public class AppointmentReminderService {

    /** Statuses that represent a still-upcoming booking worth reminding. */
    static final Set<AppointmentStatus> REMINDABLE_STATUSES = Set.of(
        AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULED);

    /** Hard cap — IKODDI gives no per-segment feedback, so keep bodies short. */
    static final int MAX_SMS_LENGTH = 320;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentRepository appointmentRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationService notificationService;
    private final SmsService smsService;
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;

    /** Hours ahead of the start time in which a reminder becomes due. */
    @Value("${hms.appointments.reminder.lead-hours:24}")
    private long leadHours;

    /**
     * Message locale. A scheduled sweep has no request locale, so this is
     * explicit config — Burkina Faso deployments default to French.
     */
    @Value("${hms.appointments.reminder.locale:fr}")
    private String reminderLocale;

    public AppointmentReminderService(
        AppointmentRepository appointmentRepository,
        NotificationPreferenceRepository preferenceRepository,
        NotificationService notificationService,
        SmsService smsService,
        MessageSource messageSource,
        PatientLocaleResolver patientLocaleResolver
    ) {
        this.appointmentRepository = appointmentRepository;
        this.patientLocaleResolver = patientLocaleResolver;
        this.preferenceRepository = preferenceRepository;
        this.notificationService = notificationService;
        this.smsService = smsService;
        this.messageSource = messageSource;
    }

    /**
     * Send reminders for appointments starting within the lead window.
     * Per-appointment failures are logged and skipped; each appointment is
     * stamped exactly once regardless of dispatch outcome.
     *
     * @return number of appointments for which at least one channel fired
     */
    @Transactional
    public int sendDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusHours(leadHours);

        List<Appointment> candidates = appointmentRepository.findAwaitingReminder(
            REMINDABLE_STATUSES, now.toLocalDate(), windowEnd.toLocalDate());

        int reminded = 0;
        for (Appointment appointment : candidates) {
            try {
                LocalDateTime startsAt = appointment.getAppointmentDate().atTime(appointment.getStartTime());
                if (startsAt.isBefore(now) || startsAt.isAfter(windowEnd)) {
                    continue; // outside the hour-precision window — picked up by a later tick
                }
                if (remind(appointment, startsAt)) {
                    reminded++;
                }
                // Stamp even when both channels were skipped, so the sweep
                // converges instead of re-evaluating the same row forever.
                appointment.setReminderSentAt(LocalDateTime.now());
                appointmentRepository.save(appointment);
            } catch (RuntimeException ex) {
                log.warn("Appointment reminder failed for {}: {}", appointment.getId(), ex.getMessage(), ex);
            }
        }
        return reminded;
    }

    /** @return true when at least one channel dispatched. */
    private boolean remind(Appointment appointment, LocalDateTime startsAt) {
        Patient patient = appointment.getPatient();
        if (patient == null) {
            return false;
        }
        Hospital hospital = appointment.getHospital();
        String hospitalName = hospital != null && hospital.getName() != null ? hospital.getName() : "";
        // The configured locale is now the fallback, not the answer. A patient
        // who stated a language we can render gets it; everyone else is
        // unchanged.
        Locale locale = patientLocaleResolver.resolve(patient, Locale.forLanguageTag(reminderLocale));
        String message = messageSource.getMessage(
            "sms.appointment.reminder",
            new Object[]{
                startsAt.toLocalDate().format(DATE_FORMAT),
                startsAt.toLocalTime().format(TIME_FORMAT),
                hospitalName
            },
            locale);
        if (message.length() > MAX_SMS_LENGTH) {
            message = message.substring(0, MAX_SMS_LENGTH);
        }

        boolean dispatched = false;
        User portalUser = patient.getUser();
        if (portalUser != null && portalUser.getUsername() != null
            && channelEnabled(portalUser, NotificationChannel.IN_APP)) {
            try {
                notificationService.createNotification(message, portalUser.getUsername(), "APPOINTMENT_REMINDER");
                dispatched = true;
            } catch (RuntimeException ex) {
                log.warn("In-app appointment reminder failed for {}: {}", appointment.getId(), ex.getMessage());
            }
        }
        if (sendSmsBestEffort(appointment, patient, portalUser, message)) {
            dispatched = true;
        }
        return dispatched;
    }

    private boolean sendSmsBestEffort(Appointment appointment, Patient patient, User portalUser, String message) {
        if (!smsService.deliversRealSms()) {
            return false; // mock transport logs bodies — never route PHI there
        }
        if (portalUser != null && !channelEnabled(portalUser, NotificationChannel.SMS)) {
            return false; // patient opted out of SMS reminders
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return false;
        }
        try {
            smsService.send(phone, message);
            return true;
        } catch (RuntimeException ex) {
            log.warn("SMS appointment reminder failed for {}: {}", appointment.getId(), ex.getMessage());
            return false;
        }
    }

    /**
     * First real consumer of the stored APPOINTMENT_REMINDER preferences:
     * an explicit disabled row turns the channel off; no row means enabled.
     */
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
