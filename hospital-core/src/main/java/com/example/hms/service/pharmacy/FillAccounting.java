package com.example.hms.service.pharmacy;

import com.example.hms.model.Prescription;

import java.math.BigDecimal;

/**
 * How much of a prescription is still owed (gap G3, round 3).
 *
 * <p>One owner for the arithmetic, in the shape of {@code
 * ControlledSubstanceGuard}: the dispense service needs it to decide whether a
 * fill is complete, and the routing service needs it so a partner, a supplier
 * or a printed copy is told the REMAINING amount rather than the full
 * prescribed one. Routing a partially filled order used to send the whole
 * quantity, which is a double-dispense waiting to happen.
 */
public final class FillAccounting {

    private FillAccounting() {
    }

    /**
     * Total quantity this prescription is entitled to across its whole life:
     * the prescribed quantity once for the original fill, plus once more for
     * every refill an approval has released.
     */
    public static BigDecimal expectedLifetimeQuantity(Prescription prescription) {
        BigDecimal perFill = perFillQuantity(prescription);
        if (perFill == null) {
            perFill = BigDecimal.ZERO;
        }
        int refillsUsed = prescription.getRefillsUsed() != null ? prescription.getRefillsUsed() : 0;
        return perFill.multiply(BigDecimal.valueOf(1L + refillsUsed));
    }

    /**
     * The quantity one fill is entitled to: the stored quantity, or for a
     * legacy prescription that predates the field, the cap read from its
     * regimen (dose x frequency x duration) - the same ceiling the dispense
     * service enforces (QA D706). QA D752: status transitions used only the
     * stored quantity, so a partial dispense of 4 against a derived cap of 9
     * closed the prescription as DISPENSED and the remaining 5 could never be
     * handed over. Null when neither is known.
     */
    public static BigDecimal perFillQuantity(Prescription prescription) {
        if (prescription.getQuantity() != null) {
            return prescription.getQuantity();
        }
        return PrescribedQuantityEstimate.perFill(prescription);
    }

    /**
     * What is still owed after {@code dispensedToDate}, or null when the
     * prescription carries no quantity at all — a null remainder means
     * "unknown", and callers must not print a number they do not have.
     * Never negative: an over-dispense owes nothing.
     */
    public static BigDecimal remaining(Prescription prescription, BigDecimal dispensedToDate) {
        if (perFillQuantity(prescription) == null) {
            return null;
        }
        BigDecimal expected = expectedLifetimeQuantity(prescription);
        BigDecimal dispensed = dispensedToDate != null ? dispensedToDate : BigDecimal.ZERO;
        BigDecimal remaining = expected.subtract(dispensed);
        return remaining.signum() > 0 ? remaining : BigDecimal.ZERO;
    }

    /**
     * The remainder as a human reads it in an SMS or on a printed copy —
     * "6" or "6 comprimés" — with the trailing zeros of a NUMERIC(12,2)
     * column stripped. Null when the remainder is unknown.
     */
    public static String remainingLabel(BigDecimal remaining, String quantityUnit) {
        if (remaining == null) {
            return null;
        }
        String amount = remaining.stripTrailingZeros().toPlainString();
        return quantityUnit != null && !quantityUnit.isBlank()
                ? amount + " " + quantityUnit.trim()
                : amount;
    }
}
