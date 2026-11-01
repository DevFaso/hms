package com.example.hms.enums;

/** QA D974: how an issued invoice was lowered, each with a mandatory reason. */
public enum InvoiceAdjustmentType {
    /** A price reduction granted by the hospital. */
    DISCOUNT,
    /** An exemption: gratuité, an indigent patient, a programme that covers the care. */
    WAIVER,
    /** The reversal of a billed line, for a service not given or billed in error. */
    CREDIT_NOTE
}
