package com.example.hms.enums;

public enum StockTransactionType {
    RECEIPT,
    DISPENSE,
    ADJUSTMENT,
    TRANSFER,
    RETURN,
    /**
     * QA D2670 (r65): stock taken off a lot by a counter sale ({@code referenceId} is the sale). Written only by
     * the sale itself, never through the manual movement form.
     */
    SALE
}
