package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocales;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The after-commit half of {@link PrescriberPharmacyNotifier}: reloads the
 * prescription in a transaction of its own, walks {@code staff.user} while a
 * session exists, and writes the notification row.
 *
 * <p>Separate from the notifier so the REQUIRES_NEW proxy is a real one — a
 * self-invocation inside the notifier would run with no transaction at all
 * and the first LAZY read would throw.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrescriberPharmacyNotificationWriter {

    /** {@code Notification.type} for every pharmacy outcome; the inbox groups on it. */
    public static final String NOTIFICATION_TYPE = "PHARMACY_EVENT";

    private static final String KEY_PREFIX = "prescription.pharmacy.event.";

    /** {@code security.notifications.message} is VARCHAR(255); a longer body fails the INSERT. */
    static final int MAX_MESSAGE_LENGTH = 255;
    private static final String PATIENT_FALLBACK_KEY = "prescription.pharmacy.patientFallback";

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    /**
     * @return true when a notification was written, false when the
     *         prescription or its prescriber account could not be resolved
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(UUID prescriptionId, PrescriptionStatus event) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId).orElse(null);
        if (prescription == null) {
            log.warn("Prescriber notification skipped: prescription {} not found", prescriptionId);
            return false;
        }
        String username = prescriberUsername(prescription);
        if (username == null) {
            log.warn("Prescriber notification skipped: prescription {} has no prescriber account",
                    prescriptionId);
            return false;
        }
        notificationService.createNotification(
                capped(message(prescription, event)), username, NOTIFICATION_TYPE);
        return true;
    }

    private static String prescriberUsername(Prescription prescription) {
        Staff staff = prescription.getStaff();
        User user = staff != null ? staff.getUser() : null;
        return user != null ? user.getUsername() : null;
    }

    /**
     * Rendered in {@link NotificationLocales#STAFF}: the row is persisted once
     * and read later by the prescriber, not by whoever triggered it.
     *
     * <p>The clarification body names the medication only. The pharmacist's
     * question is clinical narrative, stored encrypted on the prescription
     * where the prescriber reads it; the notification row is plaintext and
     * 255 characters wide, so it must not carry the question (or the patient).
     */
    private String message(Prescription prescription, PrescriptionStatus event) {
        String medication = prescription.getMedicationName() != null
                ? prescription.getMedicationName() : "";
        if (event == PrescriptionStatus.PENDING_CLARIFICATION) {
            return messageSource.getMessage(KEY_PREFIX + event.name(),
                    new Object[]{medication}, NotificationLocales.STAFF);
        }
        String patient = patientName(prescription.getPatient());
        String third = switch (event) {
            case PARTNER_ACCEPTED, PARTNER_REJECTED, PARTNER_DISPENSED -> partnerName(prescription);
            default -> "";
        };
        return messageSource.getMessage(KEY_PREFIX + event.name(),
                new Object[]{medication, patient, third}, NotificationLocales.STAFF);
    }

    /** A body the column can hold: an overlong medication name is cut, never a failed INSERT. */
    static String capped(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 1) + "\u2026";
    }

    /**
     * Which partner the message names.
     *
     * <p>The prescription's own pharmacy columns are cleared when a partner
     * refuses (the work queue must group the row under the in-house
     * dispensary), and this writer reads the committed row — so a refusal
     * would otherwise always say "the partner pharmacy" and never name who
     * refused. The routing decisions are the durable record of that, so fall
     * back to the newest one that carries a target pharmacy.
     */
    private String partnerName(Prescription prescription) {
        String name = prescription.getPharmacyName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String fromDecision = latestPartnerPharmacyName(prescription);
        if (fromDecision != null) {
            return fromDecision;
        }
        return messageSource.getMessage("prescription.pharmacy.partnerFallback", null,
                NotificationLocales.STAFF);
    }

    private String latestPartnerPharmacyName(Prescription prescription) {
        for (PrescriptionRoutingDecision decision
                : routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescription.getId())) {
            if (decision.getRoutingType() == RoutingType.PARTNER
                    && decision.getStatus() != RoutingDecisionStatus.CANCELLED
                    && decision.getTargetPharmacy() != null
                    && decision.getTargetPharmacy().getName() != null
                    && !decision.getTargetPharmacy().getName().isBlank()) {
                return decision.getTargetPharmacy().getName();
            }
        }
        return null;
    }

    private String patientName(Patient patient) {
        if (patient == null) {
            return messageSource.getMessage(PATIENT_FALLBACK_KEY, null, NotificationLocales.STAFF);
        }
        String first = patient.getFirstName() != null ? patient.getFirstName().trim() : "";
        String last = patient.getLastName() != null ? patient.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty()
                ? messageSource.getMessage(PATIENT_FALLBACK_KEY, null, NotificationLocales.STAFF)
                : full;
    }
}
