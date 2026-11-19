package com.example.hms.service.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.model.PayerPlanTerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 7: the calculator table - rules (item, category, default), exclusions, per-act, per-invoice and
 * annual ceilings, and FLOOR rounding per line (owner default D-5). The figures are the plan's acceptance criteria.
 */
class CoverageCalculatorTest {

    private static final UUID CONSULTATION = UUID.randomUUID();
    private static final UUID TDR = UUID.randomUUID();
    private static final UUID XRAY = UUID.randomUUID();

    private static PayerPlanTerms terms(int percent, String perInvoice, String annual) {
        return PayerPlanTerms.builder().defaultCoveragePercent(percent)
            .perInvoiceCeiling(perInvoice == null ? null : new BigDecimal(perInvoice))
            .annualCeilingPerBeneficiary(annual == null ? null : new BigDecimal(annual))
            .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
    }

    private static PayerPlanRule excluded(PriceListCategory category) {
        return PayerPlanRule.builder().category(category).excluded(true).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
    }

    private static CoverageCalculator.Line line(UUID id, String net, PriceListCategory category) {
        return new CoverageCalculator.Line(id, new BigDecimal(net), 1, category, null);
    }

    /** BC2: consultation 2 000 + TDR 1 500 (lab excluded) + X-ray 5 000 under RAMU 80 %. */
    private static List<CoverageCalculator.Line> bc2() {
        return List.of(line(CONSULTATION, "2000", PriceListCategory.CONSULTATION),
            line(TDR, "1500", PriceListCategory.LAB_TEST), line(XRAY, "5000", PriceListCategory.IMAGING));
    }

    @Test
    @DisplayName("BC2 with RAMU 80 %, lab excluded: 1 600 + 0 + 4 000 = 5 600; the patient's share is 2 900")
    void theWorkedExample() {
        CoverageCalculator.Result result = CoverageCalculator.compute(bc2(), terms(80, null, "150000"),
            List.of(excluded(PriceListCategory.LAB_TEST)), BigDecimal.ZERO);

        assertThat(result.shareOf(CONSULTATION)).isEqualByComparingTo("1600");
        assertThat(result.shareOf(TDR)).isEqualByComparingTo("0");
        assertThat(result.shareOf(XRAY)).isEqualByComparingTo("4000");
        assertThat(result.total()).isEqualByComparingTo("5600");
        assertThat(new BigDecimal("8500").subtract(result.total())).isEqualByComparingTo("2900");
    }

    @Test
    @DisplayName("rounding: FLOOR per line - 1 255 at 80 % is 1 004 (patient 251); 999 at 75 % is 749 (patient 250)")
    void roundingIsFloorPerLine() {
        UUID a = UUID.randomUUID();
        CoverageCalculator.Result eighty = CoverageCalculator.compute(List.of(line(a, "1255", null)),
            terms(80, null, null), List.of(), BigDecimal.ZERO);
        assertThat(eighty.total()).isEqualByComparingTo("1004");
        assertThat(new BigDecimal("1255").subtract(eighty.total())).isEqualByComparingTo("251");

        CoverageCalculator.Result seventyFive = CoverageCalculator.compute(List.of(line(a, "999", null)),
            terms(75, null, null), List.of(), BigDecimal.ZERO);
        assertThat(seventyFive.total()).isEqualByComparingTo("749");
        assertThat(new BigDecimal("999").subtract(seventyFive.total())).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("the annual ceiling: 148 000 already used of 150 000 caps a 5 600 share at 2 000, taken in line order")
    void theAnnualCeilingCapsTheShare() {
        CoverageCalculator.Result result = CoverageCalculator.compute(bc2(), terms(80, null, "150000"),
            List.of(excluded(PriceListCategory.LAB_TEST)), new BigDecimal("148000"));

        assertThat(result.total()).isEqualByComparingTo("2000");
        assertThat(result.shareOf(CONSULTATION)).isEqualByComparingTo("1600");
        assertThat(result.shareOf(XRAY)).isEqualByComparingTo("400");
        assertThat(result.lines().stream().map(CoverageCalculator.LineShare::share)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(result.total());

        CoverageCalculator.Result spent = CoverageCalculator.compute(bc2(), terms(80, null, "150000"),
            List.of(), new BigDecimal("151000"));
        assertThat(spent.total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the per-invoice ceiling caps the sum of the lines before the annual ceiling")
    void thePerInvoiceCeiling() {
        CoverageCalculator.Result result = CoverageCalculator.compute(bc2(), terms(80, "3000", "150000"),
            List.of(), BigDecimal.ZERO);
        assertThat(result.total()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("the most specific rule wins: item, then category, then the plan default; a per-act ceiling per unit")
    void precedenceAndPerActCeiling() {
        UUID item = UUID.randomUUID();
        PayerPlanRule categoryRule = PayerPlanRule.builder().category(PriceListCategory.IMAGING).coveragePercent(50)
            .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        PayerPlanRule itemRule = PayerPlanRule.builder().priceListItemId(item).coveragePercent(100)
            .perActCeiling(new BigDecimal("1500")).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        UUID byItem = UUID.randomUUID();
        UUID byCategory = UUID.randomUUID();
        UUID byDefault = UUID.randomUUID();
        List<CoverageCalculator.Line> lines = List.of(
            new CoverageCalculator.Line(byItem, new BigDecimal("4000"), 2, PriceListCategory.IMAGING, item),
            new CoverageCalculator.Line(byCategory, new BigDecimal("5000"), 1, PriceListCategory.IMAGING, null),
            new CoverageCalculator.Line(byDefault, new BigDecimal("1000"), 1, null, null));

        CoverageCalculator.Result result = CoverageCalculator.compute(lines, terms(80, null, null),
            List.of(categoryRule, itemRule), BigDecimal.ZERO);

        assertThat(result.shareOf(byItem)).as("100 %, capped at 1 500 x 2 acts").isEqualByComparingTo("3000");
        assertThat(result.shareOf(byCategory)).as("the category's 50 %").isEqualByComparingTo("2500");
        assertThat(result.shareOf(byDefault)).as("the plan's 80 %").isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("a line credited to nothing gets no share")
    void aFullyCreditedLineGetsNothing() {
        UUID a = UUID.randomUUID();
        CoverageCalculator.Result result = CoverageCalculator.compute(List.of(line(a, "0", null)),
            terms(80, null, null), List.of(), BigDecimal.ZERO);
        assertThat(result.total()).isEqualByComparingTo("0");
    }
}
