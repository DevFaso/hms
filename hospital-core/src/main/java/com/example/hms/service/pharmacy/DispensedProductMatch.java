package com.example.hms.service.pharmacy;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

/**
 * QA D3940 (r77): how the product a fill takes from the shelf relates to the product the order prescribes. The
 * server derives it from the lot (or the catalogue item named when stock is not kept in lots) on every fill, whatever
 * the client says; {@link DispenseVerificationService#classify} is the one rule.
 *
 * <p>The rule (provisional until each hospital's approver signs it off: rule set
 * {@code DISPENSED_PRODUCT_SUBSTITUTION}):
 * <ul>
 *   <li>{@link #PRESCRIBED}: the order's own catalogue item; for an order with no item, the molecule it names at
 *       the strength it states (or at any strength when its text states none).</li>
 *   <li>{@link #EQUIVALENT}: another catalogue item with the same active ingredient, the same strength and the same
 *       form, all three known. Dispensed as the prescribed product, recorded under its own name.</li>
 *   <li>{@link #SUBSTITUTE}: the same active ingredient and the same strength in another (or an unknown) form. One
 *       unit is one prescribed unit, so the quantity is dose-equivalent; it leaves only as a recorded substitution
 *       (the flag and a reason).</li>
 *   <li>{@link #OTHER_STRENGTH}: the same active ingredient at another strength, or at a strength nobody recorded.
 *       Refused, even as a substitution: a fill is counted in prescribed units, so 10 tablets of 200 mg against
 *       10 of 400 mg would close the order on half the dose (QA D3952 keeps the dose-equivalent quantity as a
 *       follow-up).</li>
 *   <li>{@link #OTHER_DRUG}: another active ingredient. Refused, always: a substitution never changes the molecule
 *       (diclofenac for ibuprofen, amoxicillin for a penicillin-allergic patient's ibuprofen).</li>
 * </ul>
 */
@ProvisionalRule(ClinicalRuleSetKey.DISPENSED_PRODUCT_SUBSTITUTION)
public enum DispensedProductMatch {
    PRESCRIBED,
    EQUIVALENT,
    SUBSTITUTE,
    OTHER_STRENGTH,
    OTHER_DRUG;

    /** Whether the product is the prescribed one (no substitution needed). */
    public boolean isPrescribedProduct() {
        return this == PRESCRIBED || this == EQUIVALENT;
    }

    /** Whether a fill may take it: the prescribed product, or a substitute when it is recorded as one. */
    public boolean mayBeDispensed(boolean recordedSubstitution) {
        return isPrescribedProduct() || (this == SUBSTITUTE && recordedSubstitution);
    }
}
