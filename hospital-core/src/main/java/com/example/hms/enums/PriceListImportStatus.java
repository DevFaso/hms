package com.example.hms.enums;

/** Billing-money plan PR 4 (V304): what a price-list CSV import did. */
public enum PriceListImportStatus {
    /** "Vérifier le fichier": every row checked, nothing written to the price list. */
    DRY_RUN,
    /** Every row applied in one transaction (an import with a refused row applies nothing and is not recorded). */
    APPLIED
}
