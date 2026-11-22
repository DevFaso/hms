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
    CONTAMINATED;

    /** True only for stock that may be sold again. */
    public boolean isFitForSale() {
        return this == INTACT;
    }
}
