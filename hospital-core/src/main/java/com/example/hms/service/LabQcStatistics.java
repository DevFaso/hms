package com.example.hms.service;

import java.math.BigDecimal;

/**
 * r63 D2304: where a QC value sits against the director's target, in SDs.
 * Standard Levey-Jennings practice: within 2 SD, outside 2 SD (a warning),
 * outside 3 SD. It flags; the operator decides whether to accept the run.
 * No flag at all when no target (or a non-positive SD) is set.
 */
public final class LabQcStatistics {

    public static final String WITHIN_2SD = "WITHIN_2SD";
    public static final String OUTSIDE_2SD = "OUTSIDE_2SD";
    public static final String OUTSIDE_3SD = "OUTSIDE_3SD";

    private LabQcStatistics() {
    }

    /** (measured - mean) / sd, or null when there is no usable target. */
    public static Double zScore(BigDecimal measured, BigDecimal mean, BigDecimal sd) {
        if (measured == null || mean == null || sd == null || sd.signum() <= 0) {
            return null;
        }
        // Decimal arithmetic: 5.4 against 5.0 +/- 0.2 is exactly 2 SD, not 2.0000000000000018.
        return measured.subtract(mean).divide(sd, java.math.MathContext.DECIMAL64).doubleValue();
    }

    /** The SD flag for a measured value, or null when there is no usable target. */
    public static String sdFlag(BigDecimal measured, BigDecimal mean, BigDecimal sd) {
        Double z = zScore(measured, mean, sd);
        if (z == null) {
            return null;
        }
        double distance = Math.abs(z);
        if (distance > 3.0) {
            return OUTSIDE_3SD;
        }
        if (distance > 2.0) {
            return OUTSIDE_2SD;
        }
        return WITHIN_2SD;
    }
}
