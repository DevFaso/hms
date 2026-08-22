package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Prescription;
import org.springframework.stereotype.Component;

/**
 * The one copy of the controlled-substance rule (P2 #15).
 *
 * <p>PRs #454 and #455 were built on parallel branches, so each shipped its own
 * copy of this rule — one in {@code PrescriptionServiceImpl} keyed on status,
 * one in {@code DispenseServiceImpl} guarding the irreversible step — with a
 * note to fold them once both merged. By the 2026-08-21 reassessment the two
 * bodies had already drifted in wording. This component is the fold; both
 * services delegate here and neither carries its own copy any more.
 *
 * <p>The rule itself: a prescription the prescriber flagged as a controlled
 * substance must have completed two-factor verification, and one flagged as
 * requiring a co-signature must actually carry one, before it may reach any
 * state that authorises somebody to act on it. Both gates apply ONLY when the
 * prescriber set the flag — nothing changes for ordinary prescriptions.
 */
@Component
public class ControlledSubstanceGuard {

    /**
     * Refuse to move {@code prescription} to {@code targetStatus} with a
     * declared safeguard unmet.
     *
     * <p>Keyed on the TARGET status, not the row's current one: a controlled
     * prescription can be drafted and saved freely, exactly as a paper one can
     * be written before it is signed. DRAFT and PENDING_SIGNATURE are always
     * permitted; everything beyond them is an actionable state.
     */
    public void requireSafeguardsFor(Prescription prescription, PrescriptionStatus targetStatus) {
        if (targetStatus == null
                || targetStatus == PrescriptionStatus.DRAFT
                || targetStatus == PrescriptionStatus.PENDING_SIGNATURE) {
            return;
        }

        if (prescription.isControlledSubstance() && prescription.getTwoFactorVerifiedAt() == null) {
            throw new BusinessException(
                "CONTROLLED_SUBSTANCE: a prescription flagged as a controlled substance cannot be "
                    + "set to " + targetStatus + " until two-factor verification is complete.");
        }

        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException(
                "COSIGN_REQUIRED: a prescription that requires a co-signature cannot be set to "
                    + targetStatus + " until a second prescriber co-signs it.");
        }
    }

    /**
     * The dispense-side check. Dispense is the irreversible step — a wrongly
     * signed prescription can be cancelled, medication handed to a patient
     * cannot be recalled — and status can be reached by paths that never pass
     * the prescribe-side gate, so the dispense path must check independently
     * rather than trust the status.
     */
    public void requireDispensable(Prescription prescription) {
        if (prescription.isControlledSubstance() && prescription.getTwoFactorVerifiedAt() == null) {
            throw new BusinessException(
                "CONTROLLED_SUBSTANCE: this prescription is flagged as a controlled substance "
                    + "and has no completed two-factor verification. It cannot be dispensed "
                    + "until the prescriber verifies it.");
        }

        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException(
                "COSIGN_REQUIRED: this prescription requires a co-signature and has not been "
                    + "co-signed. It cannot be dispensed until a second prescriber co-signs it.");
        }
    }
}
