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
    SALE,
    /**
     * QA D3403 / D3415 (r70): units set aside, not for sale: a cancelled fill that is damaged, expired or
     * contaminated, or a controlled drug a patient brought back. Counted OUT of the saleable stock and INTO the
     * quarantine. Written right after the RETURN that brought the units back (the two rows leave the lot as it was),
     * never through the manual movement form.
     */
    QUARANTINE,
    /**
     * QA D3415 (r70): quarantined units destroyed, witnessed for a controlled drug. Out of the quarantine; the
     * saleable stock does not move. Written only by the quarantine destruction.
     */
    DESTRUCTION
}
