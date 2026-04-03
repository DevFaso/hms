package com.example.hms.enums;

/**
 * CLIA/CLSI validation study types for lab test definitions.
 * Covers the standard validation protocols required by CAP/ISO 15189.
 */
public enum ValidationStudyType {
    PRECISION,
    ACCURACY,
    REFERENCE_RANGE,
    METHOD_COMPARISON,
    INTERFERENCE,
    CARRYOVER,
    LINEARITY
}
