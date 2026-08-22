package com.example.hms.enums;

/**
 * Overall growth outcome of a culture (P3 #19). Null on the entity means
 * "still pending" — finalization requires a value.
 */
public enum MicroGrowthResult {
    GROWTH,
    NO_GROWTH,
    MIXED_FLORA,
    CONTAMINATED
}
