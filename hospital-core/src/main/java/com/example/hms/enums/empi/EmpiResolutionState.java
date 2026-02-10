package com.example.hms.enums.empi;

/**
 * Indicates where a record stands in the matching / resolution workflow.
 */
public enum EmpiResolutionState {
    NEW,
    AUTO_MATCHED,
    MANUAL_REVIEW,
    CONFIRMED,
    REJECTED
}
