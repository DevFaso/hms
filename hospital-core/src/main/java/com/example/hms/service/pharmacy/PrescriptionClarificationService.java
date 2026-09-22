package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The pharmacist's clarification ceremony (gap G5).
 *
 * <p>{@code PENDING_CLARIFICATION} existed as a status and the prescriber's
 * dashboard counted it, but the only writer was a client-asserted PUT that the
 * pharmacist's role cannot call, and no column recorded the question. This is
 * now the only path into that status and the only path out of it.
 *
 * <p><b>In:</b> a pharmacist sends a dispensable order back with a reason.
 * The order leaves the work queue (it is not dispensable while the question
 * is open) and the prescriber is notified after commit.
 *
 * <p><b>Out:</b> a doctor answers and the order returns to the status it held
 * when the question was asked (SIGNED, PARTIALLY_FILLED, PENDING_STOCK…) —
 * never DRAFT, because the signing ceremony refuses to re-sign a prescription
 * that already carries a digest, so DRAFT would be a second dead end. Every
 * status a question can be raised from is dispensable, so the order is back
 * on the queue with its progress intact, and the row keeps the pharmacist's
 * question, the doctor's answer and both timestamps so the pharmacist reads
 * the exchange there ({@code attentionReason = CLARIFICATION_RESOLVED}). If
 * the doctor changed the order first, {@code updatePrescription} has no
 * status guard and the signature digest disagreeing with the row is the
 * documented evidence.
 *
 * <p><b>Who may answer:</b> any doctor with a staff profile at the
 * prescription's hospital, not only the prescriber. Signing is prescriber-only
 * because a signature is a personal act; co-signing already accepts any other
 * doctor at the hospital because it asks for a clinical judgment on the order.
 * A clarification is the second kind: the pharmacist needs an answer today,
 * and the prescriber may be off shift. The audit row and
 * {@code clarificationResolvedByUserId} record who actually answered.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrescriptionClarificationService {

    private static final String PRESCRIPTION_NOT_FOUND = "prescription.notfound";
    private static final String AUDIT_ENTITY = "PRESCRIPTION";

    private final PrescriptionRepository prescriptionRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;
    private final PrescriberPharmacyNotifier prescriberNotifier;
    private final Clock clock;

    /**
     * @param reason the pharmacist's question; required, at most 1000 chars,
     *               validated at the controller boundary
     */
    @Transactional
    public void requestClarification(UUID prescriptionId, String reason) {
        Prescription prescription = findInScope(prescriptionId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A clarification request needs a reason.");
        }
        PrescriptionStatus status = prescription.getStatus();
        if (status == null || !DispenseServiceImpl.DISPENSABLE_STATUSES.contains(status)) {
            throw new BusinessException("Only a prescription awaiting a fill can be sent back for "
                    + "clarification; this one is " + status + ".");
        }
        UUID pharmacistId = roleValidator.getCurrentUserId();
        if (pharmacistId == null) {
            throw new BusinessException("Unable to determine current user");
        }

        prescription.setClarificationPreviousStatus(status);
        prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
        prescription.setClarificationReason(reason.trim());
        prescription.setClarificationRequestedAt(LocalDateTime.now(clock));
        prescription.setClarificationRequestedByUserId(pharmacistId);
        prescription.setClarificationResponse(null);
        prescription.setClarificationResolvedAt(null);
        prescription.setClarificationResolvedByUserId(null);
        prescriptionRepository.save(prescription);

        // The reason is clinical narrative and stays off the audit description.
        support.logAudit(AuditEventType.PRESCRIPTION_CLARIFICATION_REQUESTED,
                "Clarification requested on prescription " + prescriptionId,
                prescriptionId.toString(), AUDIT_ENTITY);
        prescriberNotifier.notifyPrescriber(prescription, PrescriptionStatus.PENDING_CLARIFICATION);
        log.info("Prescription {} sent back for clarification by user {}", prescriptionId, pharmacistId);
    }

    /**
     * @param response the doctor's answer; optional (the order itself may have
     *                 been edited instead), at most 1000 chars
     */
    @Transactional
    public void resolveClarification(UUID prescriptionId, String response) {
        Prescription prescription = findInScope(prescriptionId);
        if (prescription.getStatus() != PrescriptionStatus.PENDING_CLARIFICATION) {
            throw new BusinessException("This prescription is not awaiting clarification; it is "
                    + prescription.getStatus() + ".");
        }
        Staff doctor = resolveDoctorAtHospital(prescription);

        PrescriptionStatus restored = prescription.getClarificationPreviousStatus() != null
                ? prescription.getClarificationPreviousStatus()
                : PrescriptionStatus.SIGNED;
        prescription.setStatus(restored);
        prescription.setClarificationResponse(response != null && !response.isBlank() ? response.trim() : null);
        prescription.setClarificationResolvedAt(LocalDateTime.now(clock));
        prescription.setClarificationResolvedByUserId(doctor.getUser() != null ? doctor.getUser().getId() : null);
        prescriptionRepository.save(prescription);

        support.logAudit(AuditEventType.PRESCRIPTION_CLARIFICATION_RESOLVED,
                "Clarification resolved on prescription " + prescriptionId + "; returned to " + restored,
                prescriptionId.toString(), AUDIT_ENTITY);
        log.info("Prescription {} clarification resolved by staff {}", prescriptionId, doctor.getId());
    }

    /** Same 404-not-403 idiom as the rest of the prescription surface. */
    private Prescription findInScope(UUID prescriptionId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && (prescription.getHospital() == null
                    || !hospitalId.equals(prescription.getHospital().getId()))) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }
        return prescription;
    }

    /**
     * AccessDeniedException rather than BusinessException: this is an
     * authorization failure, not a workflow one, and the caller should get 403
     * rather than 400 (the co-sign ceremony's stance).
     */
    private Staff resolveDoctorAtHospital(Prescription prescription) {
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Unable to determine the answering clinician.");
        }
        UUID rxHospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        if (rxHospitalId == null) {
            throw new AccessDeniedException(
                    "Only a clinician at the prescribing hospital can resolve a clarification.");
        }
        // Looked up at the prescription's hospital, not "the doctor's first
        // profile": a clinician credentialed at two hospitals has two.
        return staffRepository.findByUserIdAndHospitalId(currentUserId, rxHospitalId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Only a clinician with a staff profile at the prescribing hospital can "
                                + "resolve a clarification."));
    }
}
