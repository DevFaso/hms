package com.example.hms.enums;

/**
 * Policy rule 6 (provisional, V345): why a fill may leave the pharmacy with money still due, and who answers for it.
 */
public enum DispenseBillingDeferralKind {
    /** A payer covers the fill: billing checks the claim or the voucher and records its reference. */
    THIRD_PARTY_COVER,
    /** Credit granted to an outpatient by finance or the hospital admin, with a reason and an optional limit. */
    OUTPATIENT_CREDIT,
    /** The pharmacist hands over in an emergency, with a reason; finance reviews it afterwards. */
    URGENCY_OVERRIDE,
    /** An inpatient item, charged to the open stay: nobody approves it. */
    ADMISSION_ACCOUNT;

    /** A kind somebody asks for and somebody else approves before the hand-over. */
    public boolean needsApproval() {
        return this == THIRD_PARTY_COVER || this == OUTPATIENT_CREDIT;
    }
}
