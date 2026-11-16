package com.example.hms.service.billing;

import com.example.hms.enums.PriceListPriceStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.PriceListPrice;
import com.example.hms.utility.DisplayDates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Billing-money plan PR 2: the rules for a price and for a new price version, with no I/O, so each one is unit
 * tested on its own.
 *
 * <ul>
 *   <li>A price is whole francs, at least 0, at most 9 999 999 999.</li>
 *   <li>A version never starts in the past. "Today" is the hospital's business day (Africa/Ouagadougou, D-16).</li>
 *   <li>A version that has started (in force or ended) is never replaced: a new version must start after the
 *       latest started one.</li>
 *   <li>A version that has not started yet may be replaced, but only when the caller confirms it
 *       ({@code replaceScheduled}); it is then closed on the day before its own start (never in force) and stays in
 *       the history.</li>
 *   <li>The version in force before the new one is closed on the day before the new one starts.</li>
 * </ul>
 */
public final class PriceVersionRules {

    static final String FIELD_UNIT_PRICE = "unitPrice";
    static final String FIELD_EFFECTIVE_FROM = "effectiveFrom";
    static final String FIELD_REPLACE_SCHEDULED = "replaceScheduled";

    /** numeric(12,2): the largest whole amount the column holds. */
    static final BigDecimal MAX_PRICE = new BigDecimal("9999999999");

    private PriceVersionRules() {
    }

    /**
     * What adding a version starting {@code from} does to the item's existing versions.
     *
     * @param toReplace   live versions that have not started and start on or after {@code from}: each is closed on
     *                    the day before its own start
     * @param predecessor the live version that starts before {@code from}, closed on {@code from - 1}; empty for a
     *                    first price
     */
    public record Plan(List<PriceListPrice> toReplace, Optional<PriceListPrice> predecessor) {
    }

    /** Refuses a missing, decimal, negative or too large price, naming {@code unitPrice}. */
    public static void requireValidPrice(BigDecimal unitPrice) {
        if (unitPrice == null) {
            throw new FieldBusinessException(FIELD_UNIT_PRICE, "billing.priceList.priceRequired");
        }
        if (unitPrice.signum() < 0) {
            throw new FieldBusinessException(FIELD_UNIT_PRICE, "billing.priceList.priceNegative");
        }
        if (unitPrice.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(FIELD_UNIT_PRICE, "billing.priceList.priceWhole");
        }
        if (unitPrice.compareTo(MAX_PRICE) > 0) {
            throw new FieldBusinessException(FIELD_UNIT_PRICE, "billing.priceList.priceTooLarge");
        }
    }

    /** The start of a new version: today when not given; a day in the past is refused. */
    public static LocalDate startOf(LocalDate requested, LocalDate today) {
        LocalDate from = requested != null ? requested : today;
        if (from.isBefore(today)) {
            throw new FieldBusinessException(FIELD_EFFECTIVE_FROM, "billing.priceList.dateInPast");
        }
        return from;
    }

    /**
     * Plans a new version starting {@code from} (already checked by {@link #startOf}) against the item's
     * {@code versions}, read under the item's lock. {@code enteredToday} says whether a version was entered on
     * the business day (a same-day correction may replace it).
     */
    public static Plan plan(List<PriceListPrice> versions, LocalDate today, LocalDate from, boolean replaceScheduled,
                            Predicate<PriceListPrice> enteredToday) {
        List<PriceListPrice> live = versions.stream().filter(v -> !v.isReplaced()).toList();
        List<PriceListPrice> conflicting = live.stream().filter(v -> !v.getEffectiveFrom().isBefore(from)).toList();
        List<PriceListPrice> started = conflicting.stream()
            .filter(v -> !v.getEffectiveFrom().isAfter(today))
            .toList();
        // A started version is never replaced, except a same-day correction: it starts today AND was entered
        // today (provisional decision 2026-11-16); it is then replaced like a planned one, with confirmation.
        Optional<PriceListPrice> applied = started.stream()
            .filter(v -> v.getEffectiveFrom().isBefore(today) || !enteredToday.test(v))
            .max(Comparator.comparing(PriceListPrice::getEffectiveFrom));
        if (applied.isPresent()) {
            throw new FieldBusinessException(FIELD_EFFECTIVE_FROM, "billing.priceList.versionStarted",
                DisplayDates.date(applied.get().getEffectiveFrom()));
        }
        if (!started.isEmpty() && !replaceScheduled) {
            throw new FieldBusinessException(FIELD_REPLACE_SCHEDULED, "billing.priceList.correctToday",
                francs(started.get(0).getUnitPrice()));
        }
        if (!conflicting.isEmpty() && !replaceScheduled) {
            PriceListPrice first = conflicting.stream()
                .min(Comparator.comparing(PriceListPrice::getEffectiveFrom)).orElseThrow();
            throw new FieldBusinessException(FIELD_REPLACE_SCHEDULED, "billing.priceList.scheduledExists",
                francs(first.getUnitPrice()), DisplayDates.date(first.getEffectiveFrom()));
        }
        Optional<PriceListPrice> predecessor = live.stream()
            .filter(v -> v.getEffectiveFrom().isBefore(from))
            .max(Comparator.comparing(PriceListPrice::getEffectiveFrom));
        return new Plan(conflicting, predecessor);
    }

    /** Where a version stands on {@code today}. */
    public static PriceListPriceStatus statusOf(PriceListPrice version, LocalDate today) {
        if (version.isReplaced()) {
            return PriceListPriceStatus.REPLACED;
        }
        if (version.getEffectiveFrom().isAfter(today)) {
            return PriceListPriceStatus.SCHEDULED;
        }
        if (version.getEffectiveTo() != null && version.getEffectiveTo().isBefore(today)) {
            return PriceListPriceStatus.ENDED;
        }
        return PriceListPriceStatus.IN_FORCE;
    }

    /** Whole francs grouped as the reader writes them ("2 500", "2,500"). */
    static String francs(BigDecimal amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(
            org.springframework.context.i18n.LocaleContextHolder.getLocale());
        format.setGroupingUsed(true);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP).longValueExact())
            .replace(' ', ' ').replace(' ', ' ');
    }
}
