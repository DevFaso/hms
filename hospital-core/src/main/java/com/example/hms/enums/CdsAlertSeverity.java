package com.example.hms.enums;

/**
 * P-08: Severity levels for prospective Clinical Decision Support (CDS) alerts
 * surfaced at dispense time.
 *
 * <p>{@link #CRITICAL} is the only level that blocks the dispense unless a
 * pharmacist override reason is provided.
 */
public enum CdsAlertSeverity {
    NONE,
    INFO,
    WARNING,
    CRITICAL
}
