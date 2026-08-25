package com.example.hms.enums;

/**
 * Individual safety check in the dispense-time verification loop (Tier 2
 * item 34), the pharmacy-counter counterpart of {@link FiveRightsCheck} at
 * the bedside.
 *
 * <p>Deliberately three checks, not five. Dose, route and time are
 * <em>administration</em> questions: they are decided when the nurse gives
 * the drug, and the eMAR already owns them. What the pharmacist can actually
 * verify while handing a pack across a counter is who it is for, that it is
 * the right drug, and that it is fit to give.
 */
public enum DispenseCheck {

    /** Scanned wristband resolves to the prescription's patient. */
    PATIENT,

    /**
     * The scanned lot's catalogue item is the prescribed medication.
     * Overridable only as a recorded substitution — that is a real pharmacy
     * workflow (generic for brand, different strength made up to the same
     * dose), not a way round the check.
     */
    DRUG,

    /**
     * The lot is in date. Never overridable, and unlike the other two this
     * one is enforced whether or not anybody scanned anything: it needs no
     * scan to evaluate, only the lot the pharmacist already named.
     */
    EXPIRY
}
