package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.utility.RoleValidator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Policy rule 6 (provisional; review round 2 of #1244): records who stands behind a claim as cover, at the moment
 * they act on it.
 *
 * <p>The cover of a fill used to be judged on live facts: who last touched the claim, and the roles that person
 * holds today. A pharmacist recording the insurer's payment after the hand-over, or a billing clerk leaving the
 * hospital, then turned fills of closed days into unpaid differences. The fact is now written when it happens and
 * never rewritten: {@link FillClaimCover} reads only what is stored here.
 *
 * <p>The ONLY writer of a claim's voucher columns, and the only reader of the role rule behind them
 * ({@code DeferredBillingGuardTest}). Each slot is written once: the first person of a money desk (or hospital
 * admin) of the claim's hospital who sends the claim or records its acceptance or payment, then the first other
 * such person. Somebody who holds no such role at that moment records nothing, and erases nothing.
 */
public final class ClaimVouching {

    private ClaimVouching() {
    }

    /** {@code actorId} just sent the claim, or recorded the payer's acceptance or payment of it, at {@code at}. */
    public static void record(PharmacyClaim claim, UUID actorId, RoleValidator roles, LocalDateTime at) {
        UUID hospitalId = claim.getHospital() != null ? claim.getHospital().getId() : null;
        if (actorId == null || hospitalId == null || roles == null
                || !roles.vouchesForClaimCover(actorId, hospitalId)) {
            return;
        }
        if (claim.getVouchedBy() == null) {
            claim.setVouchedBy(actorId);
            claim.setVouchedAt(at);
        } else if (claim.getSecondVouchedBy() == null && !claim.getVouchedBy().equals(actorId)) {
            claim.setSecondVouchedBy(actorId);
            claim.setSecondVouchedAt(at);
        }
    }
}
