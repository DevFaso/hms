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
     * <p>This used to be worded "ready for pickup", but it only ever fires
     * once the prescription is fully DISPENSED — that is, after the hand-over.
     * There is no "ready" state in the dispense workflow ({@code
     * DispenseStatus.PENDING} is never written), so the message now says what
     * happened rather than what is about to. Failures are swallowed so they do
     * not roll back the dispense transaction. No-op if SMS service is
     * unavailable, the patient has no primary phone number, or the medication
     * name is blank.
     *
     * <p>Key {@code sms.pharmacy.dispensed}: {0} first name, {1} medication,
     * {2} pharmacy name.
     */
    void notifyDispensed(Patient patient, Pharmacy pharmacy, String medicationName) {
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
        try {
            smsService.send(phone, render("sms.pharmacy.dispensed", patientLocale(patient),
                    firstName, medicationName, pharmacyName));
        } catch (Exception e) {
            logFailure("dispensed", patient, e);
        }
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
        log.warn("Failed to send {} SMS to patient {}: {}", what, patient.getId(), e.getMessage());
    }
}
