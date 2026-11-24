package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.notification.PatientMessageGate;
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
    /** QA D2202: every patient SMS here goes through the patient's channel choice. */
    private final PatientMessageGate messageGate;
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;

    /**
     * Resolve the authenticated user, or throw if unavailable / not persisted.
     */
    User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("pharmacy.currentUserUnknown");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    /**
     * Record a SUCCESS audit event at the hospital the caller acts at. Failures to write the audit log are
     * swallowed to avoid breaking the caller's business transaction.
     *
     * <p>QA D2924 (r66; same class as r63 D2322): every row written here carried no hospital, so the audit service
     * could not anchor it on the actor's assignment and the hospital admin's audit log never listed a dispense, a
     * deferral or a claim. The acting hospital is named on every row now ({@code PharmacyAuditHospitalGuardTest}).
     */
    void logAudit(AuditEventType eventType, String description, String resourceId, String entityType) {
        logAudit(eventType, description, resourceId, entityType, actingHospitalOrNull());
    }

    /** {@link #logAudit(AuditEventType, String, String, String)} for a record whose own hospital the caller knows. */
    void logAudit(AuditEventType eventType, String description, String resourceId, String entityType,
                  UUID hospitalId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .hospitalId(hospitalId != null ? hospitalId : actingHospitalOrNull())
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

    /** The hospital this request acts at; null for a super-admin in global view. Never refuses an audit row. */
    private UUID actingHospitalOrNull() {
        try {
            return roleValidator.getCurrentHospitalId();
        } catch (RuntimeException e) {
            return null;
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
     * SMS service is unavailable or the patient has no primary phone number.
     *
     * <p>Every pharmacy SMS goes through {@link PatientMessageGate} (QA D2202):
     * a patient who turned SMS off for the prescription-refill notices
     * (MEDICATION_REFILL, the only medication preference) gets none of them, and
     * the hospital's administrators see a SKIPPED / OPTED_OUT row instead.
     *
     * <p>Key {@code sms.pharmacy.dispensed}: {0} first name, {1} pharmacy name.
     * Scorecard K4: never the medicine, which can reveal a condition to whoever
     * reads the phone ({@code MessageDisclosureGuardTest}).
     */
    void notifyDispensed(Prescription prescription, Patient patient, Pharmacy pharmacy) {
        sendAfterCommit(PatientNotificationEvent.PHARMACY_DISPENSED, "sms.pharmacy.dispensed",
                prescription, patient, pharmacy);
    }

    /**
     * G15: the fill is prepared and waiting at {@code pharmacy}. Key
     * {@code sms.pharmacy.readyForCollection}, same arguments as
     * {@link #notifyDispensed}.
     */
    void notifyReadyForCollection(Prescription prescription, Patient patient, Pharmacy pharmacy) {
        sendAfterCommit(PatientNotificationEvent.PHARMACY_READY, "sms.pharmacy.readyForCollection",
                prescription, patient, pharmacy);
    }

    /**
     * G15: a prepared fill is no longer waiting (the pharmacist cancelled it,
     * or the prescriber withdrew or changed the order). Key
     * {@code sms.pharmacy.readyCancelled}, same arguments.
     */
    void notifyReadyCancelled(Prescription prescription, Patient patient, Pharmacy pharmacy) {
        sendAfterCommit(PatientNotificationEvent.PHARMACY_READY_CANCELLED, "sms.pharmacy.readyCancelled",
                prescription, patient, pharmacy);
    }

    /**
     * G15: the one reminder for a fill still waiting after the reminder
     * window. Key {@code sms.pharmacy.readyReminder}, same arguments.
     */
    void notifyReadyReminder(Prescription prescription, Patient patient, Pharmacy pharmacy) {
        sendAfterCommit(PatientNotificationEvent.PHARMACY_READY_REMINDER, "sms.pharmacy.readyReminder",
                prescription, patient, pharmacy);
    }

    /**
     * Render now, send after commit (G15 rule 11).
     *
     * <p>The locale lookup, the render and the consent check run here, inside
     * the caller's transaction and inside the try, so a failure in any of them
     * is swallowed and cannot roll the pharmacy action back. Only plain strings
     * cross into the after-commit send ({@link PatientMessageGate#smsAfterCommit}).
     */
    private void sendAfterCommit(PatientNotificationEvent event, String key, Prescription prescription,
                                 Patient patient, Pharmacy pharmacy) {
        if (patient == null) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String pharmacyName = (pharmacy != null && pharmacy.getName() != null) ? pharmacy.getName() : "";
        try {
            // K4: the first name and the pharmacy, never the medicine (an SMS is read on a shared phone).
            String body = render(key, patientLocale(patient), firstName, pharmacyName);
            messageGate.smsAfterCommit(patient, notice(event, prescription, pharmacy), body);
        } catch (Exception e) {
            logFailure(event, patient, e);
        }
    }

    /**
     * Send the out-of-stock SMS to the patient, explaining where their
     * medication will be filled, once the routing commits. Failures are
     * swallowed.
     *
     * @param routingKey  one of the {@code OUT_OF_STOCK_*} keys — the
     *                    routing-specific sentence appended to the body,
     *                    resolved in the patient's own language
     * @param routingArgs arguments for that sentence (partner name, restock date)
     */
    void notifyOutOfStock(Prescription prescription, Patient patient, String routingKey, Object... routingArgs) {
        if (patient == null) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        try {
            Locale locale = patientLocale(patient);
            String suffix = routingKey != null ? render(routingKey, locale, routingArgs) : "";
            messageGate.smsAfterCommit(patient,
                    notice(PatientNotificationEvent.PHARMACY_OUT_OF_STOCK, prescription, null),
                    render("sms.pharmacy.outOfStock", locale, firstName, suffix));
        } catch (Exception e) {
            logFailure(PatientNotificationEvent.PHARMACY_OUT_OF_STOCK, patient, e);
        }
    }

    /**
     * T-39: Send the refill reminder SMS to the patient, indicating how many
     * days of treatment remain. No-op when there is no real SMS transport, no
     * patient or phone, or the patient turned these SMS off. Failures are
     * swallowed.
     *
     * <p>Key {@code sms.pharmacy.refillReminder}: {0} first name, {1} days
     * left. Never the medicine (K4).
     *
     * @return true when the reminder was admitted for sending
     */
    boolean notifyRefillReminder(Prescription prescription, Patient patient, int daysLeft) {
        if (patient == null) {
            return false;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return false;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        try {
            // The day count goes through as text so MessageFormat never groups it.
            return messageGate.smsAfterCommit(patient,
                    notice(PatientNotificationEvent.PHARMACY_REFILL_REMINDER, prescription, null),
                    render("sms.pharmacy.refillReminder", patientLocale(patient),
                            firstName, String.valueOf(daysLeft)));
        } catch (Exception e) {
            logFailure(PatientNotificationEvent.PHARMACY_REFILL_REMINDER, patient, e);
            return false;
        }
    }

    /**
     * The notice of a pharmacy SMS: the prescription it is about, at the
     * hospital that wrote it (the pharmacy's hospital when it has none).
     */
    private static PatientMessageGate.Notice notice(PatientNotificationEvent event, Prescription prescription,
                                                    Pharmacy pharmacy) {
        UUID hospitalId = null;
        if (prescription != null && prescription.getHospital() != null) {
            hospitalId = prescription.getHospital().getId();
        } else if (pharmacy != null && pharmacy.getHospital() != null) {
            hospitalId = pharmacy.getHospital().getId();
        }
        return new PatientMessageGate.Notice(event, hospitalId, prescription != null ? prescription.getId() : null);
    }

    private Locale patientLocale(Patient patient) {
        return patientLocaleResolver.resolve(patient, NotificationLocales.PATIENT_FALLBACK);
    }

    private String render(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale).trim();
    }

    /**
     * Everything an SMS needs (the locale lookup, the bundle render, the
     * consent check) happens inside each method's try, and lands here: a
     * bundle typo or a database hiccup must never roll back a dispense that
     * has already been handed over. The patient id and the exception class
     * only: a transport message can quote the number.
     */
    private void logFailure(PatientNotificationEvent event, Patient patient, Exception e) {
        log.warn("Failed to send {} SMS to patient {}: {}", event, patient.getId(), e.getClass().getSimpleName());
    }
}
