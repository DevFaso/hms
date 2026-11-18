package com.example.hms.enums;

/** Billing-money plan PR 4: what a call to the price-list CSV import did, as the report states it. */
public enum PriceListImportResult {
    /** "Vérifier le fichier": every row checked, nothing written to the price list. */
    DRY_RUN,
    /** Every row applied, in one transaction. */
    APPLIED,
    /** An apply with at least one refused row: nothing was written. */
    REFUSED,
    /** The same file was already applied at this hospital: nothing new was written ("déjà importé"). */
    ALREADY_IMPORTED
}
