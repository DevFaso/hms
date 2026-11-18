package com.example.hms.enums;

/** Billing-money plan PR 4: what one row of a price-list CSV import does (or would do, on a dry run). */
public enum PriceListImportRowOutcome {
    /** A code the grid does not have: a new item with its first price. */
    NEW_ITEM,
    /** A known code whose price differs on the row's effective date: a new price version from that date. */
    NEW_PRICE,
    /** A known code whose price on the row's effective date is already the row's: nothing is written. */
    UNCHANGED,
    /** The row breaks a rule (each reason is listed with its column): the whole file applies nothing. */
    REFUSED
}
