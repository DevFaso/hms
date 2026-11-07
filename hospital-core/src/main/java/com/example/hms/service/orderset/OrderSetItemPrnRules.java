package com.example.hms.service.orderset;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.impl.PrnMinInterval;
import com.example.hms.utility.MessageUtil;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA D1382 / D1378 (round 32): the as-needed part of an order set's
 * MEDICATION items, held to the prescription's own rule
 * ({@link PrnMinInterval#refusalOf}) wherever an item is written or used.
 * An order set accepted a PRN item with no interval (201), and the apply then
 * skipped it silently ({@code skippedItemCount 1}, no reason); an "SOS" item
 * was turned into a non-PRN draft.
 *
 * <ul>
 *   <li>{@code asNeeded} is the author's explicit tick on the item (V260
 *       ticked the older items the round-31 phrase list read);</li>
 *   <li>ticked: {@code prnMinIntervalHours} is required, 1 to 72 hours;</li>
 *   <li>not ticked: no interval, a condition is refused on the tick, and a
 *       wording the MAR cannot schedule is asked about on the item's
 *       {@code frequencyConfirmed} (QA D1419), once: the answer stays on the item.</li>
 * </ul>
 * {@link #requireValid} runs at save (create and every new version) and
 * again at apply, before any order is created, so an item stored before this
 * rule is refused by name, never skipped. Each refusal names the item's field
 * ({@code orderItems[2].prnMinIntervalHours}) and its position and name.
 */
public final class OrderSetItemPrnRules {

    /** The request property of the items. */
    static final String ITEMS_FIELD = "orderItems";

    /** "Item {0} ({1}): {2}" - the item's position, its name, the prescription rule's sentence. */
    static final String ITEM_REFUSED_KEY = "admissionOrderSet.item.prnRefused";

    /** A whole number written as digits in the JSON. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,4}");

    private OrderSetItemPrnRules() {
    }

    /** Refuses the first MEDICATION item whose as-needed part breaks the prescription rule. */
    public static void requireValid(List<Map<String, Object>> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (item == null || !isMedication(item)) {
                continue;
            }
            PrnMinInterval.Refusal refusal = refusalOf(item);
            if (refusal != null) {
                throw new FieldBusinessException(ITEMS_FIELD + "[" + i + "]." + refusal.field(), ITEM_REFUSED_KEY,
                    i + 1, nameOf(item), MessageUtil.resolve(refusal.key()));
            }
            // QA D1436: the same place holds a dose to its unit, so each writer and the apply get it.
            if (OrderSetItemDoseRules.lacksUnit(item)) {
                throw new FieldBusinessException(ITEMS_FIELD + "[" + i + "]." + OrderSetItemDoseRules.DOSE_UNIT_KEY,
                    ITEM_REFUSED_KEY, i + 1, nameOf(item), MessageUtil.resolve(OrderSetItemDoseRules.UNIT_REQUIRED_KEY));
            }
        }
    }

    /** The author's "as needed" tick on the item. */
    public static boolean asNeeded(Map<String, Object> item) {
        Object value = item.get(PrnMinInterval.AS_NEEDED_FIELD);
        return Boolean.TRUE.equals(value) || (value instanceof String s && "true".equalsIgnoreCase(s.strip()));
    }

    /**
     * QA D1419: the author confirmed a wording the MAR cannot schedule. Kept on
     * the item, so applying the set later is not asked again.
     */
    public static boolean frequencyConfirmed(Map<String, Object> item) {
        Object value = item.get(PrnMinInterval.CONFIRMED_FIELD);
        return Boolean.TRUE.equals(value) || (value instanceof String s && "true".equalsIgnoreCase(s.strip()));
    }

    /** The item's minimum interval when it is a whole number of hours, else {@code null}. */
    public static Integer intervalHours(Map<String, Object> item) {
        Object value = item.get(PrnMinInterval.FIELD);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l && l <= PrnMinInterval.MAX_HOURS && l >= 0) {
            return l.intValue();
        }
        if (value instanceof String s && WHOLE_NUMBER.matcher(s.strip()).matches()) {
            return Integer.valueOf(s.strip());
        }
        return null;
    }

    private static PrnMinInterval.Refusal refusalOf(Map<String, Object> item) {
        Object written = item.get(PrnMinInterval.FIELD);
        boolean blank = written == null || (written instanceof String s && s.isBlank());
        Integer hours = intervalHours(item);
        if (!blank && hours == null) {
            // Written, but not a whole number of hours (2.5, "quatre", -1).
            return new PrnMinInterval.Refusal(PrnMinInterval.FIELD, PrnMinInterval.RANGE_KEY);
        }
        Object frequency = item.get("frequency");
        return PrnMinInterval.refusalOf(asNeeded(item), hours, frequency instanceof String f ? f : null,
            frequencyConfirmed(item));
    }

    private static boolean isMedication(Map<String, Object> item) {
        return item.get("orderType") instanceof String type
            && OrderSetItemDispatcher.TYPE_MEDICATION.equals(type.strip().toUpperCase(Locale.ROOT));
    }

    private static String nameOf(Map<String, Object> item) {
        Object name = item.get("medicationName");
        return name instanceof String s && !s.isBlank() ? s.strip() : "-";
    }
}
