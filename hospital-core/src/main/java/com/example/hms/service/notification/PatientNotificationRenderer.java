package com.example.hms.service.notification;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.service.AppointmentReminderService;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.utility.DisplayDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns a claimed delivery row into the message to send, at send time (V312). Runs inside the
 * sweep's short "prepare" transaction.
 *
 * <p>Nothing is rendered until it is checked that sending is still right; otherwise the row is
 * SKIPPED with its reason and nothing is sent:
 * <ul>
 *   <li>the subject still stands: the appointment is still booked and still ahead; the lab order is
 *       not voided and has a released result; the imaging or ultrasound report is released to the
 *       patient ({@link PatientNotificationErrorCode#SUBJECT_WITHDRAWN});</li>
 *   <li>the patient has not since turned the channel off ({@code OPTED_OUT});</li>
 *   <li>the patient still has a number or an address ({@code NO_CONTACT});</li>
 *   <li>the patient has not died ({@code PATIENT_DECEASED}).</li>
 * </ul>
 *
 * <p><b>No clinical content.</b> An appointment message carries the day, the time and the hospital's
 * name, the existing reminder's text; a result message says only that a result is available at the
 * hospital and to log in. Never the test, the value, the modality, the department or a diagnosis:
 * an SMS is read on a shared phone. {@code PatientNotificationRoutingGuardTest} holds the result
 * templates to it.
 *
 * <p>The language is the patient's own ({@link PatientLocaleResolver}). When they stated none we can
 * write, it is the configured {@code hms.appointments.reminder.locale} (French by default), as the
 * reminder sweep does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientNotificationRenderer {

    /** Hard cap: the gateways give no per-segment feedback, so keep bodies short. */
    static final int MAX_SMS_LENGTH = 320;

    static final String SMS_APPOINTMENT_CONFIRMATION = "sms.appointment.confirmation";
    static final String SMS_APPOINTMENT_REMINDER = "sms.appointment.reminder";
    static final String SMS_RESULT_AVAILABLE = "sms.result.available";
    static final String EMAIL_APPOINTMENT_REMINDER_SUBJECT = "email.patient.appointmentReminder.subject";
    static final String EMAIL_APPOINTMENT_REMINDER_BODY = "email.patient.appointmentReminder.body";
    static final String EMAIL_RESULT_AVAILABLE_SUBJECT = "email.patient.resultAvailable.subject";
    static final String EMAIL_RESULT_AVAILABLE_BODY = "email.patient.resultAvailable.body";

    /** What the transactionless send step needs. {@code subject} is null for an SMS. */
    public record Prepared(NotificationChannel channel, String recipient, String subject, String body) {
        @Override
        public String toString() {
            return "Prepared[" + channel + "]"; // never the recipient or the text
        }
    }

    private final PatientNotificationDeliveryRepository repository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final AppointmentRepository appointmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final UltrasoundReportRepository ultrasoundReportRepository;
    private final PatientChannelConsent consent;
    private final PatientLocaleResolver localeResolver;
    private final MessageSource messageSource;
    private final Clock clock;
    private final PatientNotificationProperties properties;

    /**
     * The fallback language, the reminder sweep's own setting ({@code AppointmentReminderService}):
     * a sweep has no request locale, and Burkina Faso deployments default to French.
     */
    @Value("${hms.appointments.reminder.locale:fr}")
    private String fallbackLocale = "fr";

    /**
     * @return the message, or null when the row was skipped (and recorded so) or is no longer open
     */
    public Prepared prepare(UUID deliveryId) {
        PatientNotificationDelivery row = repository.findById(deliveryId).orElse(null);
        if (row == null || !row.getStatus().isOpen()) {
            return null;
        }
        if (!row.getEventType().isQueued()) {
            // A direct event's only row is the trace of an opt-out; there is nothing to render.
            return skip(row, PatientNotificationErrorCode.OPTED_OUT);
        }
        Patient patient = patientRepository.findByIdUnscoped(row.getPatientId()).orElse(null);
        PatientNotificationErrorCode refusal = recipientRefusal(row, patient);
        if (refusal != null) {
            return skip(row, refusal);
        }
        String recipient = recipientOf(row, patient);
        Locale locale = localeResolver.resolve(patient, Locale.forLanguageTag(fallbackLocale));
        String hospitalName = hospitalRepository.findById(row.getHospitalId())
            .map(Hospital::getName).filter(StringUtils::hasText).orElse("");

        Object[] args;
        if (row.getEventType().isResult()) {
            if (!resultStillReleased(row.getEventType(), row.getSubjectId())) {
                return skip(row, PatientNotificationErrorCode.SUBJECT_WITHDRAWN);
            }
            args = new Object[] {hospitalName};
        } else {
            LocalDateTime startsAt = liveAppointmentStart(row.getSubjectId());
            if (startsAt == null) {
                return skip(row, PatientNotificationErrorCode.SUBJECT_WITHDRAWN);
            }
            args = new Object[] {
                DisplayDates.date(startsAt.toLocalDate(), locale),
                DisplayDates.time(startsAt.toLocalTime(), locale),
                hospitalName
            };
        }
        return row.getChannel() == NotificationChannel.SMS
            ? new Prepared(NotificationChannel.SMS, recipient.trim(), null, sms(row.getEventType(), args, locale))
            : email(row.getEventType(), recipient.trim(), args, locale);
    }

    private String sms(PatientNotificationEvent event, Object[] args, Locale locale) {
        String message = switch (event) {
            case APPOINTMENT_CONFIRMATION -> messageSource.getMessage(SMS_APPOINTMENT_CONFIRMATION, args, locale);
            case APPOINTMENT_REMINDER -> messageSource.getMessage(SMS_APPOINTMENT_REMINDER, args, locale);
            default -> messageSource.getMessage(SMS_RESULT_AVAILABLE, args, locale);
        };
        return message.length() > MAX_SMS_LENGTH ? message.substring(0, MAX_SMS_LENGTH) : message;
    }

    private Prepared email(PatientNotificationEvent event, String address, Object[] args, Locale locale) {
        boolean result = event.isResult();
        Object[] escaped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            escaped[i] = HtmlUtils.htmlEscape(String.valueOf(args[i]));
        }
        String subject = messageSource.getMessage(
            result ? EMAIL_RESULT_AVAILABLE_SUBJECT : EMAIL_APPOINTMENT_REMINDER_SUBJECT, args, locale);
        String body = messageSource.getMessage(
            result ? EMAIL_RESULT_AVAILABLE_BODY : EMAIL_APPOINTMENT_REMINDER_BODY, escaped, locale);
        String html = "<!DOCTYPE html><html lang=\"" + locale.getLanguage() + "\"><body><p>" + body
            + "</p></body></html>";
        return new Prepared(NotificationChannel.EMAIL, address, subject, html);
    }

    /** Why this patient must not get this send now, or null when they may. */
    private PatientNotificationErrorCode recipientRefusal(PatientNotificationDelivery row, Patient patient) {
        if (patient == null) {
            return PatientNotificationErrorCode.NO_CONTACT;
        }
        if (com.example.hms.service.support.DeceasedPatientGuard.careBlocked(patient)) {
            // Never text a dead patient's phone about a visit or a result.
            return PatientNotificationErrorCode.PATIENT_DECEASED;
        }
        UUID userId = patient.getUser() != null ? patient.getUser().getId() : null;
        if (!consent.allows(userId, row.getEventType().preferenceType(), row.getChannel())) {
            return PatientNotificationErrorCode.OPTED_OUT;
        }
        String recipient = recipientOf(row, patient);
        if (!StringUtils.hasText(recipient)) {
            return PatientNotificationErrorCode.NO_CONTACT;
        }
        if (row.getChannel() == NotificationChannel.EMAIL
            && !EmailRecipientAllowlist.parse(properties.getEmailRecipientAllowlist()).permits(recipient)) {
            // Dev / uat: EMAIL_RECIPIENT_ALLOWLIST, the email twin of SMS_RECIPIENT_ALLOWLIST.
            return PatientNotificationErrorCode.RECIPIENT_NOT_ALLOWED;
        }
        return null;
    }

    private static String recipientOf(PatientNotificationDelivery row, Patient patient) {
        return row.getChannel() == NotificationChannel.SMS ? patient.getPhoneNumberPrimary() : patient.getEmail();
    }

    private boolean resultStillReleased(PatientNotificationEvent event, UUID subjectId) {
        return switch (event) {
            case LAB_RESULT_AVAILABLE -> labOrderRepository.findById(subjectId)
                .filter(order -> order.getStatus() != LabOrderStatus.ENTERED_IN_ERROR)
                .isPresent()
                && labResultRepository.findByLabOrder_Id(subjectId).stream().anyMatch(LabResult::isReleased);
            // The order's current read: its latest version, as the patient's chart shows it. A
            // newer version that is voided or not yet signed is not announced as a result.
            case IMAGING_RESULT_AVAILABLE -> imagingReportRepository
                .findByImagingOrder_IdOrderByReportVersionDesc(subjectId).stream()
                .findFirst()
                .map(ImagingReport::isReleasedToPatient).orElse(false);
            case ULTRASOUND_RESULT_AVAILABLE -> ultrasoundReportRepository.findByIdUnscoped(subjectId)
                .map(UltrasoundReport::isReleasedToPatient).orElse(false);
            default -> false;
        };
    }

    /** The start of a still-booked, still-ahead appointment; null when it is cancelled, past or gone. */
    private LocalDateTime liveAppointmentStart(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        LocalDateTime startsAt = startOf(appointment);
        if (startsAt == null || !AppointmentReminderService.REMINDABLE_STATUSES.contains(appointment.getStatus())
            || startsAt.isBefore(LocalDateTime.now(clock))) {
            return null;
        }
        return startsAt;
    }

    private static LocalDateTime startOf(Appointment appointment) {
        if (appointment == null || appointment.getAppointmentDate() == null || appointment.getStartTime() == null) {
            return null;
        }
        return appointment.getAppointmentDate().atTime(appointment.getStartTime());
    }

    private Prepared skip(PatientNotificationDelivery row, PatientNotificationErrorCode code) {
        row.setStatus(NotificationDeliveryStatus.SKIPPED);
        row.setErrorCode(code.name());
        repository.save(row);
        log.info("Patient notification {} ({} {}) skipped: {}", row.getId(), row.getEventType(), row.getChannel(),
            code);
        return null;
    }
}
