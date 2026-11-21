package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;

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
 * <p>A claim counts when it is SUBMITTED, ACCEPTED or PAID and one of its recorded vouchers
 * ({@code vouched_by}, {@code second_vouched_by}) is not the person handing over. Anything else (a draft, a rejected
 * claim, a claim only the pharmacy touched) covers nothing: the fill goes through the deferral gate.
 *
 * <p>Review round 2: only STORED facts are read here. Who vouched is recorded when it happens
 * ({@link ClaimVouching}); this class never asks what role anybody holds today, nor who touched the claim last, so
 * the cover of a fill handed over on a closed day does not move when a pharmacist later records the payment or when
 * the billing clerk leaves the hospital ({@code DeferredBillingGuardTest}). No state: the gate, the reconciliation
 * and the « still due » balance ask the same question here.
 */
public final class FillClaimCover {

    /** The states in which a claim may cover a fill at all. */
    static final Set<PharmacyClaimStatus> SENT = EnumSet.of(PharmacyClaimStatus.SUBMITTED,
            PharmacyClaimStatus.ACCEPTED, PharmacyClaimStatus.PAID);

    private FillClaimCover() {
    }

    /** What the claims of {@code dispenseId} cover for {@code handingOver}; zero when nobody else stands behind them. */
    public static BigDecimal coveredFor(PharmacyClaimRepository claims, UUID dispenseId, UUID handingOver) {
        BigDecimal covered = BigDecimal.ZERO;
        if (claims == null || dispenseId == null) {
            return covered;
        }
        for (PharmacyClaim claim : claims.findByDispenseIdAndClaimStatusIn(dispenseId, SENT)) {
            if (claim.getAmount() != null && counts(claim, handingOver)) {
                covered = covered.add(claim.getAmount());
            }
        }
        return covered;
    }

    /** Whether somebody recorded as standing behind the claim is not the person handing over. */
    static boolean counts(PharmacyClaim claim, UUID handingOver) {
        if (claim.getClaimStatus() == null || !SENT.contains(claim.getClaimStatus())) {
            return false;
        }
        return isSomebodyElse(claim.getVouchedBy(), handingOver)
                || isSomebodyElse(claim.getSecondVouchedBy(), handingOver);
    }

    /** Why the claims sent for a fill do not cover it for the person handing over. */
    public enum Gap {
        /** No claim was sent, or every claim sent counts. */
        NONE,
        /** A claim was sent, and the only person recorded behind it is the one handing over. */
        OWN_VOUCHER,
        /** A claim was sent, and nobody of a money desk is recorded behind it. */
        NO_VOUCHER
    }

    /**
     * QA D2931 (r66): the refusal said « n'est pas payée » for a fill whose claim covers it for anybody else. Why
     * a claim sent for {@code dispenseId} does not count for {@code handingOver}, so the counter is told what to
     * do next: another person stands behind the claim (or hands over), or a money desk confirms it. Reads the
     * stored vouchers only, as {@link #counts} does.
     */
    public static Gap gapFor(PharmacyClaimRepository claims, UUID dispenseId, UUID handingOver) {
        Gap gap = Gap.NONE;
        if (claims == null || dispenseId == null) {
            return gap;
        }
        for (PharmacyClaim claim : claims.findByDispenseIdAndClaimStatusIn(dispenseId, SENT)) {
            if (claim.getAmount() == null || counts(claim, handingOver)) {
                continue;
            }
            if (claim.getVouchedBy() != null) {
                return Gap.OWN_VOUCHER;
            }
            gap = Gap.NO_VOUCHER;
        }
        return gap;
    }

    private static boolean isSomebodyElse(UUID voucher, UUID handingOver) {
        return voucher != null && !voucher.equals(handingOver);
    }
}
