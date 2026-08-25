package com.example.hms.enums;

/**
 * Perinatal death categories.
 *
 * <p>A stillbirth is not a neonatal death and the two are never summed: a
 * stillborn infant was never born alive, so it has no neonatal period. The
 * split at seven days is the early / late neonatal boundary the perinatal
 * mortality indicators use.
 */
public enum PerinatalDeathType {
    /** Died before or during birth, never born alive. */
    STILLBIRTH,
    /** Died within the first 7 days of life. */
    EARLY_NEONATAL,
    /** Died between 7 and 28 days of life. */
    LATE_NEONATAL
}
