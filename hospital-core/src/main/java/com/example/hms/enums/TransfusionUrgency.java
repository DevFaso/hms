package com.example.hms.enums;

/**
 * How fast the blood is needed.
 *
 * <p>EMERGENCY is the case that matters: in a postpartum haemorrhage there is
 * no time for a full crossmatch, and group O Rh-negative is released
 * uncrossmatched. The request records that this is what happened rather than
 * leaving an unexplained gap in the ledger.
 */
public enum TransfusionUrgency {
    ROUTINE,
    URGENT,
    EMERGENCY
}
