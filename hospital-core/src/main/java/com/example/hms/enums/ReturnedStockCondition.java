package com.example.hms.enums;

/**
 * QA D3403 / D3415 (r70): the state of medicine that comes back to the pharmacy (a prepared fill that is cancelled,
 * a patient's return). Only {@link #INTACT} stock (never given, seal unbroken) goes back on sale; any other state is
 * quarantined, then destroyed. A controlled drug that comes back from a patient is quarantined whatever its state.
 */
public enum ReturnedStockCondition {
    /** Not given, intact: back on sale. */
    INTACT,
    DAMAGED,
    EXPIRED,
    CONTAMINATED,
    /**
     * Review of #1285: a prepared fill whose stock is gone (« manquant »). Nothing comes back: the units are a
     * recorded loss. Only for a cancelled preparation, never for a return (a return brings units back).
     */
    MISSING;

    /** True only for stock that may be sold again. */
    public boolean isFitForSale() {
        return this == INTACT;
    }

    /** True when nothing came back to the pharmacy (the units are a loss, not a quarantine). */
    public boolean isLoss() {
        return this == MISSING;
    }
}
