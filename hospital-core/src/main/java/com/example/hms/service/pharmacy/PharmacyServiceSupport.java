package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SmsService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.utility.TransactionCallbacks;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Shared helpers for pharmacy services (current-user resolution, audit logging
 * and the patient-facing SMS). Extracted to avoid duplication between
 * {@link DispenseServiceImpl} and {@link StockOutRoutingServiceImpl}.
 *
 * <p>Every SMS body here comes from the message bundle (gap G14), rendered in
 * the patient's stated language via {@link PatientLocaleResolver} with the
 * French product default as fallback. The French wording is the source of
 * truth and is unchanged from the literals it replaced; English and Spanish
 * were added alongside.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PharmacyServiceSupport {

    /** Routing sentence appended to the out-of-stock SMS: sent to a partner pharmacy ({0}). */
    static final String OUT_OF_STOCK_PARTNER = "sms.pharmacy.outOfStock.partner";
    /** Routing sentence: printed for the patient to take anywhere. */
    static final String OUT_OF_STOCK_PRINT = "sms.pharmacy.outOfStock.print";
    /** Routing sentence: back-ordered, no restock estimate. */
    static final String OUT_OF_STOCK_BACKORDER = "sms.pharmacy.outOfStock.backorder";
    /** Routing sentence: back-ordered, restock estimated at {0}. */
    static final String OUT_OF_STOCK_BACKORDER_DATED = "sms.pharmacy.outOfStock.backorder.dated";

    private final RoleValidator roleValidator;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final SmsService smsService;
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;

    /**
     * Resolve the authenticated user, or throw if unavailable / not persisted.
     */
    User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    /**
     * Record a SUCCESS audit event. Failures to write the audit log are swallowed
     * to avoid breaking the caller's business transaction.
     */
    void logAudit(AuditEventType eventType, String description, String resourceId, String entityType) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType(entityType)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }

    /**
     * Send the dispensed receipt SMS to the patient (gap G15).
     *
     * <p>It fires once the prescription is fully DISPENSED, that is after the
     * hand-over: from the one-step dispense, or from the hand-over of a
     * prepared fill. "Ready for collection" has its own message
     * ({@link #notifyReadyForCollection}). Sent after commit, like every
     * message here: a dispense that rolls back sends nothing. No-op if the
     * SMS service is unavailable, the patient has no primary phone number,
     * or the medication name is blank.
     *
     * <p>Key {@code sms.pharmacy.dispensed}: {0} first name, {1} medication,
     * {2} pharmacy name.
     */
    void notifyDispensed(Patient patient, Pharmacy pharmacy, String medicationName) {
        sendAfterCommit("dispensed", "sms.pharmacy.dispensed", patient, pharmacy, medicationName);
    }

    /**
     * G15: the fill is prepared and waiting at {@code pharmacy}. Key
     * {@code sms.pharmacy.readyForCollection}, same arguments as
     * {@link #notifyDispensed}.
     */
    void notifyReadyForCollection(Patient patient, Pharmacy pharmacy, String medicationName) {
        sendAfterCommit("ready for collection", "sms.pharmacy.readyForCollection",
                patient, pharmacy, medicationName);
    }

    /**
     * G15: a prepared fill is no longer waiting (the pharmacist cancelled it,
     * or the prescriber withdrew or changed the order). Key
     * {@code sms.pharmacy.readyCancelled}, same arguments.
     */
    void notifyReadyCancelled(Patient patient, Pharmacy pharmacy, String medicationName) {
        sendAfterCommit("no longer ready", "sms.pharmacy.readyCancelled", patient, pharmacy, medicationName);
    }

    /**
     * G15: the one reminder for a fill still waiting after the reminder
     * window. Key {@code sms.pharmacy.readyReminder}, same arguments.
     */
    void notifyReadyReminder(Patient patient, Pharmacy pharmacy, String medicationName) {
        sendAfterCommit("ready reminder", "sms.pharmacy.readyReminder", patient, pharmacy, medicationName);
    }

    /**
     * Render now, send after commit (G15 rule 11).
     *
     * <p>The locale lookup and the render run here, inside the caller's
     * transaction and inside the try, so a failure in either is swallowed
     * and cannot roll the pharmacy action back. Only plain strings (the
     * phone, the body, the patient id for the log) cross into the callback:
     * by then the persistence context is closed. With no transaction on the
     * thread the send runs at once ({@link TransactionCallbacks}).
     */
    private void sendAfterCommit(String what, String key, Patient patient, Pharmacy pharmacy,
                                 String medicationName) {
        if (smsService == null || patient == null) {
            return;
        }
        if (medicationName == null || medicationName.isBlank()) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String pharmacyName = (pharmacy != null && pharmacy.getName() != null) ? pharmacy.getName() : "";
        UUID patientId = patient.getId();
        String body;
        try {
            body = render(key, patientLocale(patient), firstName, medicationName, pharmacyName);
        } catch (Exception e) {
            logFailure(what, patientId, e);
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                smsService.send(phone, body);
            } catch (Exception e) {
                logFailure(what, patientId, e);
            }
        });
    }

    /**
     * Send the out-of-stock SMS to the patient, explaining where their
     * medication will be filled. Failures are swallowed.
     *
     * @param routingKey  one of the {@code OUT_OF_STOCK_*} keys — the
     *                    routing-specific sentence appended to the body,
     *                    resolved in the patient's own language
     * @param routingArgs arguments for that sentence (partner name, restock date)
     */
    void notifyOutOfStock(Patient patient, String medicationName, String routingKey, Object... routingArgs) {
        if (smsService == null || patient == null) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String medication = medicationName != null ? medicationName : "";
        try {
            Locale locale = patientLocale(patient);
            String suffix = routingKey != null ? render(routingKey, locale, routingArgs) : "";
            smsService.send(phone, render("sms.pharmacy.outOfStock", locale, firstName, medication, suffix));
        } catch (Exception e) {
            logFailure("out-of-stock", patient, e);
        }
    }

    /**
     * T-39: Send the refill reminder SMS to the patient, indicating how many
     * days of treatment remain. No-op when SMS service / patient / phone is
     * missing. Failures are swallowed.
     *
     * <p>Key {@code sms.pharmacy.refillReminder}: {0} first name, {1} days
     * left, {2} medication.
     */
    void notifyRefillReminder(Patient patient, String medicationName, int daysLeft) {
        if (smsService == null || patient == null) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String medication = medicationName != null ? medicationName : "";
        try {
            // The day count goes through as text so MessageFormat never groups it.
            smsService.send(phone, render("sms.pharmacy.refillReminder", patientLocale(patient),
                    firstName, String.valueOf(daysLeft), medication));
        } catch (Exception e) {
            logFailure("refill reminder", patient, e);
        }
    }

    private Locale patientLocale(Patient patient) {
        return patientLocaleResolver.resolve(patient, NotificationLocales.PATIENT_FALLBACK);
    }

    private String render(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale).trim();
    }

    /**
     * Everything an SMS needs — the locale lookup, the bundle render and the
     * gateway call — happens inside each method's try, and lands here.
     *
     * <p>The locale resolver runs a query and {@code getMessage} throws on a
     * missing key: with either outside the guard, a bundle typo or a database
     * hiccup would roll back a dispense that has already been handed over,
     * which is the rollback-only trap in reverse. Nothing about telling a
     * patient their medication is ready may undo the fact that it is.
     */
    private void logFailure(String what, Patient patient, Exception e) {
        logFailure(what, patient.getId(), e);
    }

    private void logFailure(String what, UUID patientId, Exception e) {
        log.warn("Failed to send {} SMS to patient {}: {}", what, patientId, e.getMessage());
    }
}
