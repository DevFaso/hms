package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 6 (provisional; review of #1244, HIGH): what the claims of a fill cover at the counter, for the person
 * handing it over.
 *
 * <p>The counter used to subtract every claim "sent to the payer" from what is due. A pharmacist alone could then
 * create a claim for the whole charge, submit it, and hand the fill over with nothing due: no approver, no urgency
 * override, no review, and nothing in the reconciliation. A claim is a third-party cover like any other, so the same
 * rule holds as for a deferral: somebody of the money desks stands behind it, and never the person who hands the
 * fill over.
 *
 * <p>A claim counts when it is
 * <ul>
 *   <li>ACCEPTED or PAID, and the payer's answer was recorded ({@code decided_by}) by a money desk or a hospital
 *       admin of the hospital who is not the person handing over. A claim decided before V345 names nobody: its
 *       submitter is then the only person known to stand behind it;</li>
 *   <li>SUBMITTED, by a money desk or a hospital admin of the hospital who is not the person handing over.</li>
 * </ul>
 * Anything else (a draft, a rejected claim, a claim only the pharmacy touched) covers nothing: the fill goes
 * through the deferral gate. No state: both the gate and the reconciliation ask the same question here.
 */
public final class FillClaimCover {

    /** The states in which a claim may cover a fill at all. */
    static final Set<PharmacyClaimStatus> SENT = EnumSet.of(PharmacyClaimStatus.SUBMITTED,
            PharmacyClaimStatus.ACCEPTED, PharmacyClaimStatus.PAID);

    private FillClaimCover() {
    }

    /**
     * What the claims of {@code dispenseId} cover for {@code handingOver} at {@code hospitalId}; zero when nobody
     * else stands behind them.
     */
    public static BigDecimal coveredFor(PharmacyClaimRepository claims, RoleValidator roles, UUID dispenseId,
                                        UUID hospitalId, UUID handingOver) {
        BigDecimal covered = BigDecimal.ZERO;
        if (claims == null || dispenseId == null) {
            return covered;
        }
        for (PharmacyClaim claim : claims.findByDispenseIdAndClaimStatusIn(dispenseId, SENT)) {
            if (claim.getAmount() != null && counts(claim, roles, hospitalId, handingOver)) {
                covered = covered.add(claim.getAmount());
            }
        }
        return covered;
    }

    /** Whether somebody of the money desks, other than the person handing over, stands behind the claim. */
    static boolean counts(PharmacyClaim claim, RoleValidator roles, UUID hospitalId, UUID handingOver) {
        if (claim.getClaimStatus() == null || !SENT.contains(claim.getClaimStatus())) {
            return false;
        }
        UUID voucher = voucherOf(claim);
        return voucher != null && !voucher.equals(handingOver) && roles != null
                && roles.vouchesForClaimCover(voucher, hospitalId);
    }

    /** Who answers for the claim: whoever recorded the payer's answer, else whoever sent it. */
    private static UUID voucherOf(PharmacyClaim claim) {
        if (claim.getClaimStatus() != PharmacyClaimStatus.SUBMITTED && claim.getDecidedBy() != null) {
            return claim.getDecidedBy();
        }
        return claim.getSubmittedByUser() != null ? claim.getSubmittedByUser().getId() : null;
    }
}
