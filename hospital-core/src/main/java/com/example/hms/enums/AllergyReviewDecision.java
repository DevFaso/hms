package com.example.hms.enums;

/**
 * Policy 4 (user recommendation 2026-11-10, pending clinical approval): an authorized prescriber's
 * decision on an allergy conflict found after the signature (CurrentAllergyRecheck).
 */
public enum AllergyReviewDecision {
    /** Give and dispense the order as reviewed, under the recorded plan. */
    CONTINUE,
    /** Do not give or dispense the order: staff contact the prescriber. */
    STOP
}
