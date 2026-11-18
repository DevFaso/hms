package com.example.hms.enums;

/**
 * Billing-money plan PR 3 (V303, plan section 3.3): where an invoice line's unit price came from.
 */
public enum InvoiceLinePriceSource {
    /** The hospital's price list: the version in force on the service date, copied onto the line. */
    TARIFF,
    /** A service ({@code clinical.treatments}) whose name matched: the rule before the price list. */
    SERVICE_NAME,
    /** Priced by a person at the till: a typed line, an unpriced line priced, or a tariff overridden with a reason. */
    MANUAL,
    /** Posted at 0 for the cashier to price; issuing asks for confirmation (QA D1467). */
    UNPRICED,
    /** Written before V303. */
    LEGACY
}
