package com.example.hms.service.billing;

import com.example.hms.enums.PayerCoverageStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.utility.DisplayDates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 6: the rules for a payer plan's terms and for a new plan rule, with no I/O, so each one is
 * unit tested on its own.
 *
 * <ul>
 *   <li>A coverage rate is a whole percent from 0 to 100. A decimal is refused, never rounded.</li>
 *   <li>A ceiling (per invoice, per beneficiary and year, per act) is whole francs, above 0, at most
 *       9 999 999 999. An exclusion is how a payer pays nothing; a ceiling of 0 is refused.</li>
 *   <li>A rule names exactly one target: a price-list category or one price-list item.</li>
 *   <li>An exclusion carries no rate and no ceiling; any other rule sets a rate, a ceiling, or both.</li>
 *   <li>A plan or a rule never starts in the past. "Today" is the hospital's business day (Africa/Ouagadougou).</li>
 *   <li>Rules are versioned like prices: a new rule closes the one in force for the same target on the day before it
 *       starts. A rule planned for the same target on or after the new start is replaced only when the caller
 *       confirms it ({@code replaceScheduled}); the question names its start. A replaced rule is closed on the day
 *       before its own start (never applies) and stays in the history.</li>
 * </ul>
 */
public final class PayerCoverageRules {

    static final String FIELD_EFFECTIVE_FROM = "effectiveFrom";
    static final String FIELD_EFFECTIVE_TO = "effectiveTo";
    static final String FIELD_REPLACE_SCHEDULED = "replaceScheduled";
    static final String FIELD_CATEGORY = "category";
    static final String FIELD_ITEM = "priceListItemId";
    static final String FIELD_COVERAGE_PERCENT = "coveragePercent";
    static final String FIELD_PER_ACT_CEILING = "perActCeiling";

    /** numeric(12,2): the largest whole amount the column holds. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PayerCoverageRules() {
    }

    /**
     * What adding a rule starting {@code from} does to the plan's rules for the same target.
     *
     * @param predecessor the rule in force before {@code from} and still open on it: closed on {@code from - 1}
     * @param toReplace   the live rules starting on or after {@code from}: each closed on the day before its own start
     */
    public record RulePlan(Optional<PayerPlanRule> predecessor, List<PayerPlanRule> toReplace) {
    }

    /** A whole percent from 0 to 100, or the refusal naming {@code field}. Null stays null (the caller requires it). */
    public static Integer percentOf(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(field, "billing.payer.percentWhole");
        }
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new FieldBusinessException(field, "billing.payer.percentRange");
        }
        return value.intValueExact();
    }

    /** Whole francs above 0 (scale 2, as the column holds them), or the refusal naming {@code field}; null stays null. */
    public static BigDecimal ceilingOf(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(field, "billing.payer.ceilingWhole");
        }
        if (value.signum() <= 0) {
            throw new FieldBusinessException(field, "billing.payer.ceilingPositive");
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            throw new FieldBusinessException(field, "billing.payer.ceilingTooLarge");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The start of a plan or a rule: today when not given; a day in the past is refused. */
    public static LocalDate startOf(LocalDate requested, LocalDate today) {
        LocalDate from = requested != null ? requested : today;
        if (from.isBefore(today)) {
            throw new FieldBusinessException(FIELD_EFFECTIVE_FROM, "billing.payer.dateInPast");
        }
        return from;
    }

    /**
     * Exactly one target. Both named: refused on the item; neither: refused on the category (the first choice on the
     * form).
     */
    public static void requireOneTarget(PriceListCategory category, UUID itemId) {
        if (category != null && itemId != null) {
            throw new FieldBusinessException(FIELD_ITEM, "billing.payer.ruleTargetBoth");
        }
        if (category == null && itemId == null) {
            throw new FieldBusinessException(FIELD_CATEGORY, "billing.payer.ruleTargetRequired");
        }
    }

    /** An exclusion with no rate and no ceiling; any other rule with at least one of them. */
    public static void requireCoherentTerms(boolean excluded, Integer percent, BigDecimal perActCeiling) {
        if (excluded && percent != null) {
            throw new FieldBusinessException(FIELD_COVERAGE_PERCENT, "billing.payer.ruleExcludedWithTerms");
        }
        if (excluded && perActCeiling != null) {
            throw new FieldBusinessException(FIELD_PER_ACT_CEILING, "billing.payer.ruleExcludedWithTerms");
        }
        if (!excluded && percent == null && perActCeiling == null) {
            throw new FieldBusinessException(FIELD_COVERAGE_PERCENT, "billing.payer.ruleEmpty");
        }
    }

    /**
     * Plans a new rule starting {@code from} (already checked by {@link #startOf}) against the plan's {@code rules}
     * for the same target, read under the payer's lock.
     */
    public static RulePlan plan(List<PayerPlanRule> rules, LocalDate from, boolean replaceScheduled) {
        List<PayerPlanRule> live = rules.stream().filter(rule -> !rule.isCancelled()).toList();
        List<PayerPlanRule> later = new ArrayList<>();
        for (PayerPlanRule rule : live) {
            if (!rule.getEffectiveFrom().isBefore(from)) {
                later.add(rule);
            }
        }
        if (!later.isEmpty() && !replaceScheduled) {
            LocalDate first = later.stream().map(PayerPlanRule::getEffectiveFrom).min(LocalDate::compareTo)
                .orElse(from);
            throw new FieldBusinessException(FIELD_REPLACE_SCHEDULED, "billing.payer.ruleScheduledExists",
                DisplayDates.date(first));
        }
        Optional<PayerPlanRule> predecessor = live.stream()
            .filter(rule -> rule.getEffectiveFrom().isBefore(from))
            .filter(rule -> rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(from))
            .findFirst();
        return new RulePlan(predecessor, List.copyOf(later));
    }

    /** Where a plan stands on {@code today}. */
    public static PayerCoverageStatus statusOf(PayerPlan plan, LocalDate today) {
        return statusOf(plan.isWithdrawn(), plan.getEffectiveFrom(), plan.getEffectiveTo(), today);
    }

    /** Where a rule stands on {@code today}. */
    public static PayerCoverageStatus statusOf(PayerPlanRule rule, LocalDate today) {
        return statusOf(rule.isCancelled(), rule.getEffectiveFrom(), rule.getEffectiveTo(), today);
    }

    private static PayerCoverageStatus statusOf(boolean cancelled, LocalDate from, LocalDate to, LocalDate today) {
        if (cancelled) {
            return PayerCoverageStatus.CANCELLED;
        }
        if (from.isAfter(today)) {
            return PayerCoverageStatus.SCHEDULED;
        }
        if (to != null && to.isBefore(today)) {
            return PayerCoverageStatus.ENDED;
        }
        return PayerCoverageStatus.IN_FORCE;
    }
}
