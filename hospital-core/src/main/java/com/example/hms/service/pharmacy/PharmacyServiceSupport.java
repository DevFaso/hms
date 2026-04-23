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
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared helpers for pharmacy services (current-user resolution and audit logging).
 * Extracted to avoid duplication between {@link DispenseServiceImpl} and
 * {@link StockOutRoutingServiceImpl}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PharmacyServiceSupport {

    private final RoleValidator roleValidator;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final SmsService smsService;

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
     * Send a French ready-for-pickup SMS to the patient. Failures are swallowed so
     * they do not roll back the dispense transaction. No-op if SMS service is
     * unavailable, the patient has no primary phone number, or the medication name
     * is blank.
     *
     * <p>Template (French):
     * <code>Bonjour {firstName}, votre ordonnance ({medication}) est prête
     * à être récupérée à {pharmacy}. Merci.</code>
     */
    void notifyReadyForPickup(Patient patient, Pharmacy pharmacy, String medicationName) {
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
        String medication = medicationName;
        String pharmacyName = (pharmacy != null && pharmacy.getName() != null) ? pharmacy.getName() : "";
        String message = String.format(
                "Bonjour %s, votre ordonnance (%s) est prête à être récupérée à %s. Merci.",
                firstName, medication, pharmacyName).trim();
        try {
            smsService.send(phone, message);
        } catch (Exception e) {
            log.warn("Failed to send ready-for-pickup SMS to patient {}: {}",
                    patient.getId(), e.getMessage());
        }
    }

    /**
     * Send a French out-of-stock SMS to the patient, explaining where their
     * medication will be filled. Safe to call with any routing type; the
     * message wording adapts. Failures are swallowed.
     *
     * @param routingMessage the routing-specific French sentence to append,
     *                       e.g. "Elle a été envoyée à {partner}." or
     *                       "Veuillez l'apporter dans une pharmacie de votre choix."
     */
    void notifyOutOfStock(Patient patient, String medicationName, String routingMessage) {
        if (smsService == null || patient == null) {
            return;
        }
        String phone = patient.getPhoneNumberPrimary();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName() : "";
        String medication = medicationName != null ? medicationName : "";
        String suffix = routingMessage != null ? routingMessage : "";
        String message = String.format(
                "Bonjour %s, le médicament (%s) n'est pas disponible à la pharmacie de l'hôpital. %s",
                firstName, medication, suffix).trim();
        try {
            smsService.send(phone, message);
        } catch (Exception e) {
            log.warn("Failed to send out-of-stock SMS to patient {}: {}",
                    patient.getId(), e.getMessage());
        }
    }

    /**
     * T-39: Send a French refill reminder SMS to the patient, indicating how many
     * days of treatment remain. No-op when SMS service / patient / phone is missing.
     * Failures are swallowed.
     *
     * <p>Template (French):
     * <code>Bonjour {firstName}, il vous reste environ {daysLeft} jours de traitement
     * ({medication}). Pensez à renouveler votre ordonnance. Merci.</code>
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
        String message = String.format(
                "Bonjour %s, il vous reste environ %d jours de traitement (%s). "
                        + "Pensez à renouveler votre ordonnance. Merci.",
                firstName, daysLeft, medication).trim();
        try {
            smsService.send(phone, message);
        } catch (Exception e) {
            log.warn("Failed to send refill reminder SMS to patient {}: {}",
                    patient.getId(), e.getMessage());
        }
    }
}
