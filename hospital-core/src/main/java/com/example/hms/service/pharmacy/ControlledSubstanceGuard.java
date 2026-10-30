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
 * prescriber set the flag — nothing changes for ordinary prescriptions. (QA
 * D922, 2026-10-28: "controlled" itself is the catalogue's answer everywhere
 * else; see the note in {@link #requireSafeguardsFor}.)
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

        // QA D922: user decision 2026-10-28 SUPERSEDES D654 ("the guard keys
        // only on the prescriber's tick"). A drug the catalogue marks
        // controlled is controlled whatever the prescriber ticked, and every
        // controlled safeguard the product has keys on
        // PrescriptionControlledResolver: the pharmacist's verification before
        // a dispense or a dose, the stock witness, the controlled register,
        // the prescribed-quantity rule, the refill refusal and the controlled
        // mark on the MAR, chart, history and copies (ControlledFlagGuardTest).
        // This two-factor clause keys on the tick. Round 18 built the step it
        // demands: signPrescription re-authenticates the prescriber for EVERY
        // controlled order (tick or catalogue, PrescriptionControlledResolver)
        // and writes twoFactorVerifiedAt before this check, so a signed
        // controlled order always carries the proof. The clause stays on the
        // tick here because the dispense side (requireDispensable) must not
        // strand catalogue-controlled orders signed before round 18; the
        // signing choke point is where the catalogue rule is enforced.
        if (prescription.isControlledSubstance() && prescription.getTwoFactorVerifiedAt() == null) {
            // QA D1000: a key resolved in the caller's language; the code
            // (CONTROLLED_SUBSTANCE) stays in the key for log searches.
            throw new BusinessException("prescription.safeguard.CONTROLLED_SUBSTANCE.status",
                targetStatus.localizedLabel());
        }

        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException("prescription.safeguard.COSIGN_REQUIRED.status",
                targetStatus.localizedLabel());
        }
    }

    /**
     * Review of round 18: the co-sign half of {@link #requireSafeguardsFor},
     * checked by the signature BEFORE the prescriber re-authenticates, so a
     * refusal for a missing co-signature never spends their TOTP step or a
     * backup code on a signature that cannot happen.
     */
    public void requireCosignedFor(Prescription prescription, PrescriptionStatus targetStatus) {
        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException("prescription.safeguard.COSIGN_REQUIRED.status",
                targetStatus.localizedLabel());
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
            throw new BusinessException("prescription.safeguard.CONTROLLED_SUBSTANCE.dispense");
        }

        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException("prescription.safeguard.COSIGN_REQUIRED.dispense");
        }
    }
}
