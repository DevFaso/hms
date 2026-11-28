package com.example.hms.enums;

/**
 * Insurer claims plan, owner decision of 2026-11-26 (Q7): why an amount owed by a payer is given up. The code
 * separates a loss after recovery was exhausted, a commercial waiver or amicable settlement, and a contractual
 * adjustment (a tariff reduction, which is not a bad debt).
 */
public enum ClaimWriteOffReason {
    IRRECOVERABLE_AFTER_RECOVERY,
    COMMERCIAL_WAIVER,
    CONTRACTUAL_ADJUSTMENT
}
