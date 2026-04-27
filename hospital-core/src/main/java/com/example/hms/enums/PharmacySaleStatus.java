package com.example.hms.enums;

/**
 * P-07: Lifecycle status for an OTC walk-in {@code PharmacySale}.
 * <p>
 * Sales are intentionally separate from {@code Dispense} (insured / prescription-bound)
 * and {@code PharmacyPayment} (per-dispense payment record). Distinct status enum
 * avoids coupling the two flows in case OTC requirements diverge from clinical.
 */
public enum PharmacySaleStatus {
    DRAFT,
    COMPLETED,
    REFUNDED,
    CANCELLED
}
