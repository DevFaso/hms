package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Patient;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The one door every patient SMS and e-mail sent inline goes through (QA D2201, D2202, D2206).
 *
 * <p>The ledger ({@link PatientNotificationLedger}) already honoured the patient's channel choice
 * for the queued notices. The appointment e-mails, the visit-summary e-mail, the pharmacy SMS, the
 * waitlist and recall SMS and the invoice e-mail did not: each called the SMS or mail service
 * itself, so a patient who turned e-mail off for appointments still got the booking e-mail. Here
 * each send is checked against {@link PatientChannelConsent} for the preference its
 * {@link PatientNotificationEvent} names, and a send the patient refused is not made and leaves a
 * SKIPPED / OPTED_OUT row in the ledger, which the hospital's administrators see on
 * « Notifications aux patients ». The row never holds the message, the number or the address.
 *
 * <p>Call inside the caller's transaction: the consent check reads the patient's portal account.
 * A patient with no portal account cannot opt out yet (QA D2203, a follow-up) and is allowed.
 *
 * <p>{@code PatientMessageGateGuardTest} fails the build when a class outside this gate and a
 * reviewed list of security, staff and transport senders reaches the SMS or mail service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientMessageGate {

    private final PatientChannelConsent consent;
    private final PatientNotificationLedger ledger;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PatientRecipientVerification verification;
    private final PatientRecipientCurrentCheck currentCheck;

    /**
     * What a message announces and about what: the event names the preference that turns it off,
     * the hospital and subject (appointment, encounter, prescription, invoice, recall, waitlist
     * entry) identify the trace row. A null hospital or subject still applies the opt-out; only
     * the trace is not written.
     */
    public record Notice(PatientNotificationEvent event, UUID hospitalId, UUID subjectId) {
        public Notice {
            Objects.requireNonNull(event, "event");
        }
    }

    /** The appointment e-mail's content; the recipient is always the patient's address on record. */
    public record AppointmentMail(String patientName, String hospitalName, String staffName, String date,
                                  String time, String hospitalEmail, String hospitalPhone, String rescheduleLink,
                                  String cancelLink, Locale locale) {
    }

    /** A mail whose recipients the staff typed (the invoice). */
    public record OutgoingMail(List<String> to, List<String> cc, List<String> bcc, String subject, String html) {
        public OutgoingMail {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            bcc = bcc == null ? List.of() : List.copyOf(bcc);
        }

        @Override
        public String toString() {
            return "OutgoingMail[" + (to.size() + cc.size() + bcc.size()) + " recipients]"; // never the addresses
        }
    }

    /**
     * Whether the patient allows this notice on this channel. A refusal is recorded (the trace).
     * For the in-app bell use {@link #allowsInApp}: it has no ledger trace.
     */
    public boolean allows(Patient patient, Notice notice, NotificationChannel channel) {
        if (patient == null) {
            return false;
        }
        UUID userId = patient.getUser() != null ? patient.getUser().getId() : null;
        var decision = consent.decision(userId, notice.event().preferenceType(), channel);
        if (decision.allowed()) {
            return true;
        }
        ledger.recordOptOut(notice.event(), notice.hospitalId(), patient.getId(), notice.subjectId(), channel, decision);
        log.info("Patient {} opted out of {} by {}: not sent", patient.getId(), notice.event(), channel);
        return false;
    }

    /** The in-app bell for a portal account: the patient's choice for that kind of notice. */
    public boolean allowsInApp(Patient patient, NotificationType type) {
        if (patient == null || patient.getUser() == null) {
            return false;
        }
        return consent.decision(patient.getUser().getId(), type, NotificationChannel.IN_APP).allowed();
    }

    /**
     * Send an SMS now, on the calling thread. Only over a real transport (the logging mock must
     * never be handed patient text), to the patient's primary number, when they allow it. A
     * gateway failure propagates to the caller.
     *
     * @return true when the message was handed to the gateway
     */
    public boolean smsNow(Patient patient, Notice notice, String body) {
        String phone = smsRecipient(patient, notice, body);
        if (phone == null) {
            return false;
        }
        smsService.send(phone, body);
        return true;
    }

    /**
     * Decide now, inside the caller's transaction, and send once it commits (nothing when it rolls
     * back; at once when there is no transaction). A gateway failure is logged with the patient id
     * only and swallowed: the action that asked for the message has already happened.
     *
     * @return true when the message was admitted for sending
     */
    public boolean smsAfterCommit(Patient patient, Notice notice, String body) {
        String phone = smsRecipient(patient, notice, body);
        if (phone == null) {
            return false;
        }
        UUID patientId = patient.getId();
        PatientNotificationEvent event = notice.event();
        boolean deferred = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        TransactionCallbacks.afterCommit(() -> {
            try {
                if (deferred && !currentCheck.isVerified(patientId, NotificationChannel.SMS, phone)) {
                    ledger.recordUnverified(event, notice.hospitalId(), patientId, notice.subjectId(), NotificationChannel.SMS);
                    return;
                }
                smsService.send(phone, body);
            } catch (RuntimeException e) {
                log.warn("Failed to send {} SMS to patient {}: {}", event, patientId, e.getClass().getSimpleName());
            }
        });
        return true;
    }

    private String smsRecipient(Patient patient, Notice notice, String body) {
        if (patient == null || !StringUtils.hasText(body) || !smsService.deliversRealSms()) {
            return null;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (!StringUtils.hasText(phone) || !allows(patient, notice, NotificationChannel.SMS)) {
            return null;
        }
        return verified(patient, notice, NotificationChannel.SMS, phone) ? phone : null;
    }

    /**
     * The appointment e-mail matching the notice's event, to the patient's address on record.
     * Failures of the mail service propagate.
     *
     * @return true when the mail was queued
     */
    public boolean appointmentEmail(Patient patient, Notice notice, AppointmentMail mail) {
        String to = patient != null ? patient.getEmail() : null;
        if (!StringUtils.hasText(to) || !allows(patient, notice, NotificationChannel.EMAIL)
            || !verified(patient, notice, NotificationChannel.EMAIL, to)) {
            return false;
        }
        VerifiedPatientMailScope.run(patient.getId(), to, () -> {
        switch (notice.event()) {
            case APPOINTMENT_CONFIRMATION -> emailService.sendAppointmentConfirmationEmail(to, mail.patientName(),
                mail.hospitalName(), mail.staffName(), mail.date(), mail.time(), mail.hospitalEmail(),
                mail.hospitalPhone(), mail.rescheduleLink(), mail.cancelLink(), mail.locale());
            case APPOINTMENT_RESCHEDULED -> emailService.sendAppointmentRescheduledEmail(to, mail.patientName(),
                mail.hospitalName(), mail.staffName(), mail.date(), mail.time(), mail.hospitalEmail(),
                mail.hospitalPhone(), mail.rescheduleLink(), mail.cancelLink(), mail.locale());
            case APPOINTMENT_CANCELLED -> emailService.sendAppointmentCancelledEmail(to, mail.patientName(),
                mail.hospitalName(), mail.staffName(), mail.date(), mail.time(), mail.hospitalEmail(),
                mail.hospitalPhone(), mail.locale());
            case APPOINTMENT_COMPLETED -> emailService.sendAppointmentCompletedEmail(to, mail.patientName(),
                mail.hospitalName(), mail.staffName(), mail.date(), mail.time(), mail.hospitalEmail(),
                mail.hospitalPhone(), mail.locale());
            case APPOINTMENT_NO_SHOW -> emailService.sendAppointmentNoShowEmail(to, mail.patientName(),
                mail.hospitalName(), mail.staffName(), mail.date(), mail.time(), mail.hospitalEmail(),
                mail.hospitalPhone(), mail.locale());
            // A programming error, never shown to a client: the event names itself.
            default -> throw new IllegalArgumentException(notice.event().name());
        }
        });
        return true;
    }

    /**
     * An HTML mail to the patient at {@code to} (their address on record, or their portal
     * account's). Failures of the mail service propagate.
     *
     * @return true when the mail was queued
     */
    public boolean htmlEmail(Patient patient, Notice notice, String to, String subject, String html) {
        if (!StringUtils.hasText(to) || !allows(patient, notice, NotificationChannel.EMAIL)
            || !verified(patient, notice, NotificationChannel.EMAIL, to)) {
            return false;
        }
        VerifiedPatientMailScope.run(patient.getId(), to,
            () -> emailService.sendHtml(List.of(to), List.of(), List.of(), subject, html));
        return true;
    }

    /**
     * A mail with an attachment, sent now, to recipients the staff chose. The caller has already settled who they
     * may be (the invoice: the patient's own address, a guarantor's on file, or another address with a recorded
     * reason; see {@code InvoiceMailRecipients}). When any recipient is the patient's own address (on record or on
     * the portal account, case and spaces ignored), it must be verified and the patient's choice applies to the
     * whole send: a mail the patient refused is not sent to anyone, and the caller tells the clerk why. Failures
     * of the mail service propagate.
     *
     * @return false when the patient refused it or their address is not verified (and the trace was written)
     */
    public boolean emailWithAttachment(Patient patient, Notice notice, OutgoingMail mail, byte[] attachment,
                                       String filename, String contentType) {
        if (addressesPatient(patient, mail) && !allows(patient, notice, NotificationChannel.EMAIL)) {
            return false;
        }
        if (addressesPatient(patient, mail)) {
            List<String> own = Stream.of(patient.getEmail(), patient.getUser() != null ? patient.getUser().getEmail() : null)
                .filter(StringUtils::hasText).map(PatientMessageGate::normalised).toList();
            boolean unverified = Stream.of(mail.to(), mail.cc(), mail.bcc()).flatMap(List::stream)
                .filter(StringUtils::hasText).filter(address -> own.contains(normalised(address)))
                .anyMatch(address -> !verifiedNow(patient, notice, address));
            if (unverified) return false;
        }
        emailService.sendWithAttachment(mail.to(), mail.cc(), mail.bcc(), mail.subject(), mail.html(),
            attachment, filename, contentType);
        return true;
    }

    private static boolean addressesPatient(Patient patient, OutgoingMail mail) {
        if (patient == null) {
            return false;
        }
        List<String> own = Stream.of(patient.getEmail(),
                patient.getUser() != null ? patient.getUser().getEmail() : null)
            .filter(StringUtils::hasText).map(PatientMessageGate::normalised).toList();
        if (own.isEmpty()) {
            return false;
        }
        return Stream.of(mail.to(), mail.cc(), mail.bcc()).flatMap(List::stream)
            .filter(Objects::nonNull).map(PatientMessageGate::normalised).anyMatch(own::contains);
    }

    private boolean verified(Patient patient, Notice notice, NotificationChannel channel, String recipient) {
        if (verification.allows(patient, channel, recipient)) return true;
        ledger.recordUnverified(notice.event(), notice.hospitalId(), patient.getId(), notice.subjectId(), channel);
        return false;
    }

    /** A PDF may have been composed after this patient snapshot was loaded. */
    private boolean verifiedNow(Patient patient, Notice notice, String recipient) {
        if (!verified(patient, notice, NotificationChannel.EMAIL, recipient)) return false;
        if (patient.getId() != null && currentCheck.isVerified(patient.getId(), NotificationChannel.EMAIL, recipient)) return true;
        ledger.recordUnverified(notice.event(), notice.hospitalId(), patient.getId(), notice.subjectId(), NotificationChannel.EMAIL);
        return false;
    }

    private static String normalised(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
