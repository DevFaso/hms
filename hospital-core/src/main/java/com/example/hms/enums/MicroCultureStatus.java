package com.example.hms.enums;

/**
 * Lifecycle of a microbiology culture report (P3 #19).
 *
 * <p>Naming follows {@code ImagingReportStatus}, the one existing
 * preliminary/final vocabulary in the codebase. A culture is born
 * PRELIMINARY (Gram stain day 1, identification day 2-3), FINAL once the
 * lab attests it, and CORRECTED when a finalized report is amended — the
 * report never silently reverts to editable.
 */
public enum MicroCultureStatus {
    PRELIMINARY,
    FINAL,
    CORRECTED
}
