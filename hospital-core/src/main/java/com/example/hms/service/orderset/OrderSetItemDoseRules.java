package com.example.hms.service.orderset;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA D1436 (round 34): how a MEDICATION item of an order set says its dose.
 * The editor writes a range and a unit ({@code doseRangeMin}, {@code doseRangeMax},
 * {@code doseUnit}); the API and older items write {@code dose} and
 * {@code doseUnit}, and the unit was dropped ("2 IV" for "2 mg IV", on the draft
 * and on the MAR).
 *
 * <p>The dispatcher and the save rule read the dose through here, so what a
 * saved item says is what its order carries. A number with no unit is refused
 * by name ({@link OrderSetItemPrnRules#requireValid}), at save and at apply: a
 * dose that does not say what it counts is not an order anyone should give.
 */
final class OrderSetItemDoseRules {

    /** "Item {0} ({1}): {2}" takes this sentence. */
    static final String UNIT_REQUIRED_KEY = "admissionOrderSet.item.doseUnitRequired";

    static final String DOSE_UNIT_KEY = "doseUnit";

    /** A number or a range of numbers and nothing else: "2", "2,5", "2-4". */
    private static final Pattern BARE_NUMBER = Pattern.compile("\\d++(?:[.,]\\d++)?+(?:\\s*+-\\s*+\\d++(?:[.,]\\d++)?+)?+");

    private OrderSetItemDoseRules() {
    }

    /** The dose as its order reads it ("2 mg", "2-4 mg", "1 comprimé"), or {@code null} when the item states none. */
    static String doseOf(Map<String, Object> item) {
        String written = numberOrText(item.get("dose"));
        String amount = written != null ? written : rangeOf(item);
        if (amount == null) {
            return null;
        }
        String unit = text(item.get(DOSE_UNIT_KEY));
        return unit != null && isBareNumber(amount) ? amount + " " + unit : amount;
    }

    /** Whether the item's dose is a number that says nothing of what it counts. */
    static boolean lacksUnit(Map<String, Object> item) {
        String dose = doseOf(item);
        return dose != null && isBareNumber(dose);
    }

    private static boolean isBareNumber(String amount) {
        return BARE_NUMBER.matcher(amount.strip()).matches();
    }

    private static String rangeOf(Map<String, Object> item) {
        String min = numberOrText(item.get("doseRangeMin"));
        String max = numberOrText(item.get("doseRangeMax"));
        if (min == null || max == null || min.equals(max)) {
            return min == null ? max : min;
        }
        return min + "-" + max;
    }

    private static String numberOrText(Object value) {
        if (value instanceof Number n) {
            // In the reader's own writing, as a prescriber types a dose ("2,5 mg"); the dose reading takes both.
            return com.example.hms.utility.DisplayNumbers.quantity(new BigDecimal(n.toString()));
        }
        return text(value);
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }
}
