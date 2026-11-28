package com.example.hms.enums;

/**
 * QA D4178 (V466): what the laboratory records about a bag that was issued and never hung.
 */
public enum BloodUnitReturnOutcome {
    /** Back at the laboratory, cold chain kept and bag intact: it re-enters stock. */
    FIT_FOR_STOCK,
    /** Back at the laboratory and not fit to be given: destroyed. Nobody received it. */
    UNFIT_DESTROYED,
    /** Never came back: out of stock for good, and whatever follow-up its release opened stays open. */
    NOT_RECOVERED
}
