package com.example.hms.service.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.model.PayerPlanTerms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 7 (plan section 3.4): the payer's share of an invoice, with no I/O, so every rule is unit
 * tested on its own.
 *
 * <ol>
 *   <li><b>Per line</b>, the most specific rule in force on the service date applies: a rule on the line's price-list
 *       item, else a rule on its category, else the plan's default rate. An excluded line gets nothing.</li>
 *   <li>The line's share is {@code FLOOR(line net x rate / 100)} in whole francs (owner default D-5: never claim a
 *       fraction the payer will cut; the patient pays the remainder). The line net is the line total less its credit
 *       notes still in force; a discount or a waiver lowers only the patient's share (D-6), so it is not taken off
 *       here.</li>
 *   <li>A per-act ceiling caps the line at {@code ceiling x quantity} (each unit is one act).</li>
 *   <li>The invoice's share is the sum of the lines, capped by the per-invoice ceiling, then by what is left of the
 *       annual ceiling per beneficiary ({@code annualUsed} is what this coverage's other live shares already used in
 *       the year, read under the coverage's row lock).</li>
 *   <li>A cap is taken from the lines in invoice order: each line keeps what still fits, so the lines always add up to
 *       the invoice's share.</li>
 * </ol>
 *
 * <p>Totals are sums of lines, never a percentage of the total.
 */
public final class CoverageCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CoverageCalculator() {
    }

    /**
     * One invoice line as the calculator sees it.
     *
     * @param id              the line's id
     * @param net             the line total less its live credit notes (whole francs)
     * @param quantity        the number of acts on the line
     * @param category        the price-list category of the line, or null when it has none
     * @param priceListItemId the price-list item the line was priced from, or null
     */
    public record Line(UUID id, BigDecimal net, int quantity, PriceListCategory category, UUID priceListItemId) {
    }

    /** The share of each line (same order as given) and of the invoice. */
    public record Result(List<LineShare> lines, BigDecimal total) {

        /** The share of the line {@code id}, or 0 when it is not one of the lines. */
        public BigDecimal shareOf(UUID id) {
            return lines.stream().filter(line -> line.id().equals(id)).map(LineShare::share).findFirst()
                .orElse(BigDecimal.ZERO);
        }
    }

    /** The payer's part of one line. */
    public record LineShare(UUID id, BigDecimal share) {
    }

    /**
     * The payer's share of {@code lines} under {@code terms} and the {@code rules} in force on the service date.
     *
     * @param annualUsed what this coverage's other live shares already used in the coverage year
     */
    public static Result compute(List<Line> lines, PayerPlanTerms terms, List<PayerPlanRule> rules,
                                 BigDecimal annualUsed) {
        List<BigDecimal> raw = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal share = lineShare(line, terms, rules);
            raw.add(share);
            sum = sum.add(share);
        }
        BigDecimal capped = sum;
        if (terms.getPerInvoiceCeiling() != null) {
            capped = capped.min(terms.getPerInvoiceCeiling());
        }
        if (terms.getAnnualCeilingPerBeneficiary() != null) {
            BigDecimal used = annualUsed != null ? annualUsed : BigDecimal.ZERO;
            BigDecimal left = terms.getAnnualCeilingPerBeneficiary().subtract(used).max(BigDecimal.ZERO);
            capped = capped.min(left);
        }
        List<LineShare> shares = new ArrayList<>();
        BigDecimal room = capped;
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal kept = raw.get(i).min(room);
            room = room.subtract(kept);
            shares.add(new LineShare(lines.get(i).id(), whole(kept)));
        }
        return new Result(List.copyOf(shares), whole(capped));
    }

    /** One line's share before the invoice's ceilings. */
    static BigDecimal lineShare(Line line, PayerPlanTerms terms, List<PayerPlanRule> rules) {
        BigDecimal net = line.net() != null ? line.net() : BigDecimal.ZERO;
        if (net.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Optional<PayerPlanRule> rule = ruleFor(line, rules);
        if (rule.isPresent() && rule.get().isExcluded()) {
            return BigDecimal.ZERO;
        }
        Integer rulePercent = rule.map(PayerPlanRule::getCoveragePercent).orElse(null);
        int percent = rulePercent != null ? rulePercent : terms.getDefaultCoveragePercent();
        BigDecimal share = net.multiply(BigDecimal.valueOf(percent)).divide(HUNDRED, 0, RoundingMode.FLOOR);
        if (rule.isPresent() && rule.get().getPerActCeiling() != null) {
            BigDecimal ceiling = rule.get().getPerActCeiling().multiply(BigDecimal.valueOf(Math.max(1, line.quantity())));
            share = share.min(ceiling);
        }
        return share;
    }

    /** The most specific rule for the line: its item's, else its category's; none when neither applies. */
    static Optional<PayerPlanRule> ruleFor(Line line, List<PayerPlanRule> rules) {
        if (line.priceListItemId() != null) {
            Optional<PayerPlanRule> item = rules.stream()
                .filter(rule -> rule.getPriceListItemId() != null && rule.getPriceListItemId().equals(line.priceListItemId()))
                .findFirst();
            if (item.isPresent()) {
                return item;
            }
        }
        if (line.category() == null) {
            return Optional.empty();
        }
        return rules.stream().filter(rule -> rule.getCategory() == line.category()).findFirst();
    }

    private static BigDecimal whole(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
