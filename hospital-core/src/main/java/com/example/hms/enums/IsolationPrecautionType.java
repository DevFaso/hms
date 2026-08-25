package com.example.hms.enums;

/**
 * Transmission-based isolation precautions (Tier 2 item 32).
 *
 * <p>These are the categories layered ON TOP of standard precautions, which
 * apply to every patient and are therefore not modelled here — recording
 * "standard" against one patient would imply the others are exempt.
 *
 * <p>A patient can be on several at once, which is why they live in a child
 * table rather than as one column on the admission: a viral haemorrhagic
 * fever is {@link #CONTACT} and {@link #DROPLET} together.
 */
public enum IsolationPrecautionType {

    /**
     * Spread by touching the patient or their surroundings — cholera, C.
     * difficile, scabies, and the resistant organisms. Gown and gloves.
     */
    CONTACT,

    /**
     * Spread by large respiratory droplets that fall within about a metre —
     * influenza, pertussis, meningococcal disease. Surgical mask.
     */
    DROPLET,

    /**
     * Spread by droplet nuclei that stay airborne and travel — TB, measles,
     * varicella. Needs a respirator and, critically, a room that will not
     * vent into the rest of the ward, which is why this is the one type that
     * constrains where the patient may be placed.
     */
    AIRBORNE,

    /**
     * Protects the PATIENT from the environment rather than the environment
     * from the patient — neutropenia, transplant. The direction is inverted,
     * so a bed-placement rule that treats it like the others gets it exactly
     * backwards.
     */
    PROTECTIVE;

    /**
     * Whether this precaution constrains which ward the patient may occupy.
     *
     * <p>Only AIRBORNE does. Contact and droplet are managed with barrier
     * technique at the bedside and do not require a different ward;
     * protective isolation shields the patient rather than the ward, and
     * conflating the two would send a neutropenic patient to an infectious
     * ward.
     */
    public boolean requiresIsolationWard() {
        return this == AIRBORNE;
    }
}
