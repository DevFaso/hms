package com.example.hms.enums;

/** Whether a classified death meets the WHO perinatal figure's definition. Null on a death with no classification. */
public enum PerinatalReportingEligibility {
    /** The category, the birth status, the timing and the gestation all support it. */
    ELIGIBLE,
    /** Outside the definition: a late neonatal death, or a known gestation under the WHO start. */
    NOT_ELIGIBLE,
    /** A fact it depends on is missing. It stays counted, and is shown as unconfirmed. */
    UNKNOWN
}
