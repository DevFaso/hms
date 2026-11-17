package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.InventoryItem;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * The one whole-unit rule for every stock-moving path (QA D668, D779).
 *
 * <p>An item whose unit is not a measure (ml, g, UI, ...) is counted:
 * tablets, capsules, vials, boxes. A counted item never moves by a fraction,
 * or the shelf shows 3001434.5 tablets. D668 guarded returns only, the first
 * D779 fix added receipts and manual movements, and the dispense still
 * accepted 2.5 then 9.5 tablets because it had its own copy of none of it.
 * Every path that takes a caller-supplied quantity (fill, prepared fill,
 * partial fill, receipt, adjustment, transfer, return, counter sale) asks
 * this class; {@code StockMovementWholeUnitGuardTest} keeps it that way.
 *
 * <p>A reversal (cancelling a fill or a prepared fill) restores exactly what
 * the original movement took. It takes no quantity from the caller, and
 * refusing it would trap a legacy fractional fill on the shelf for good. For
 * the same reason an adjustment that exactly clears a legacy fraction is let
 * through ({@code StockTransactionServiceImpl}).
 */
public final class WholeUnitQuantity {

    /** The refusal when no path-specific wording applies. */
    public static final String DEFAULT_KEY = "stock.quantity.wholeUnitsOnly";

    /** Units a quantity is measured (not counted) in; anything else is counted. */
    static final Set<String> MEASURED_UNITS = Set.of(
            "ml", "l", "cl", "dl", "g", "mg", "kg", "mcg", "µg", "ug", "ui", "iu");

    private WholeUnitQuantity() {
    }

    /** Refuses a fraction of an item counted in whole units. */
    public static void require(InventoryItem item, BigDecimal quantity) {
        require(item, quantity, DEFAULT_KEY);
    }

    /** The same rule, refused with {@code messageKey}. */
    public static void require(InventoryItem item, BigDecimal quantity, String messageKey) {
        // An item with no unit on record is counted: the safe reading.
        requireForUnit(item == null || item.getUnit() == null ? "" : item.getUnit(), quantity, messageKey);
    }

    /**
     * The rule for a unit named by text (a fill with no lot names its unit on
     * the request or the prescription). {@code null} means the unit is
     * unknown and nothing is refused; an empty string means counted.
     */
    public static void requireForUnit(String unit, BigDecimal quantity, String messageKey) {
        if (unit == null || !isFraction(quantity)) {
            return;
        }
        if (!isMeasured(unit)) {
            throw new BusinessException(messageKey);
        }
    }

    /** Whether {@code unit} is a measure, in which a fraction is legitimate. */
    public static boolean isMeasured(String unit) {
        return unit != null && MEASURED_UNITS.contains(unit.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code quantity} is a whole number (null is not). */
    public static boolean isWhole(BigDecimal quantity) {
        return quantity != null && quantity.stripTrailingZeros().scale() <= 0;
    }

    private static boolean isFraction(BigDecimal quantity) {
        return quantity != null && !isWhole(quantity);
    }
}
