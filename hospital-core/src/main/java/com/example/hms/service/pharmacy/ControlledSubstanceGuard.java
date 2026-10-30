package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Prescription;
import lombok.RequiredArgsConstructor;
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
 * state that authorises somebody to act on it - signing, every later status,
 * and the dispense. "Controlled" is {@link PrescriptionControlledResolver}'s
 * answer (the prescriber's tick OR the catalogue item's flag, QA D922, user
 * decision 2026-10-28: "a drug the catalogue marks as controlled is always
 * treated as controlled, whatever the prescriber ticked"); the co-signature
 * gate applies when the order requires one. Nothing changes for ordinary
 * prescriptions.
 */
@Component
@RequiredArgsConstructor
public class ControlledSubstanceGuard {

    /** The one answer to "is this order controlled": the tick or the catalogue. */
    private final PrescriptionControlledResolver controlledResolver;

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
        // controlled is controlled whatever the prescriber ticked. Round 18
        // built the step this clause demands: signPrescription re-authenticates
        // the prescriber for EVERY controlled order (tick or catalogue) and
        // writes twoFactorVerifiedAt before this check. The round-18 merge
        // keys this clause, and the dispense one below, on the same resolver
        // (ControlledFlagGuardTest), so a catalogue-controlled order signed
        // before round 18 without the proof is refused at the dispense, as a
        // ticked order without the proof always was, instead of being handed
        // over as an ordinary drug.
        if (controlledResolver.isControlled(prescription) && prescription.getTwoFactorVerifiedAt() == null) {
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
     * rather than trust the status. "Controlled" is the catalogue-or-tick
     * answer here too (QA D922, round-18 merge): before, a catalogue-controlled
     * order with the tick unset was dispensed with no prescriber proof.
     */
    public void requireDispensable(Prescription prescription) {
        if (controlledResolver.isControlled(prescription) && prescription.getTwoFactorVerifiedAt() == null) {
            throw new BusinessException("prescription.safeguard.CONTROLLED_SUBSTANCE.dispense");
        }

        if (prescription.isRequiresCosign()
                && (prescription.getCosignedAt() == null || prescription.getCosignedBy() == null)) {
            throw new BusinessException("prescription.safeguard.COSIGN_REQUIRED.dispense");
        }
    }
}
