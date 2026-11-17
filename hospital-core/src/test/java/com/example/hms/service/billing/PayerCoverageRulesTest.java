package com.example.hms.service.billing;

import com.example.hms.enums.PayerCoverageStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing-money plan PR 6: the payer plan rules on their own (no I/O). Rates are whole percents 0-100, ceilings
 * whole francs above 0, a rule has exactly one target and coherent terms, and a new rule closes the one in force
 * for the same target, replacing a planned one only when the caller confirms it.
 */
@DisplayName("Payer coverage rules")
class PayerCoverageRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @BeforeEach
    void french() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static FieldBusinessException refusal(Runnable call) {
        try {
            call.run();
        } catch (FieldBusinessException refused) {
            return refused;
        }
        throw new AssertionError("expected a refusal");
    }

    @ParameterizedTest(name = "{0} % is accepted")
    @ValueSource(strings = {"0", "75", "100", "80.00"})
    void aWholePercentFromZeroToHundredIsAccepted(String value) {
        assertThat(PayerCoverageRules.percentOf(new BigDecimal(value), "defaultCoveragePercent"))
            .isEqualTo(new BigDecimal(value).intValueExact());
    }

    @ParameterizedTest(name = "{0} % is refused, naming the field")
    @ValueSource(strings = {"-1", "101", "250"})
    void aPercentOutsideZeroToHundredIsRefused(String value) {
        FieldBusinessException refused = refusal(() ->
            PayerCoverageRules.percentOf(new BigDecimal(value), "defaultCoveragePercent"));
        assertThat(refused.getField()).isEqualTo("defaultCoveragePercent");
        assertThat(refused.getMessage()).isEqualTo("Le taux est compris entre 0 et 100 %.");
    }

    @Test
    void aDecimalPercentIsRefusedNeverRounded() {
        FieldBusinessException refused = refusal(() ->
            PayerCoverageRules.percentOf(new BigDecimal("80.5"), "coveragePercent"));
        assertThat(refused.getField()).isEqualTo("coveragePercent");
        assertThat(refused.getMessage()).isEqualTo("Saisissez le taux en pourcentage entier, sans décimales.");
    }

    @Test
    void aCeilingIsWholeFrancsAboveZero() {
        BigDecimal annual = PayerCoverageRules.ceilingOf(new BigDecimal("150000"), "annualCeilingPerBeneficiary");
        assertThat(annual).isEqualByComparingTo("150000");
        assertThat(annual.scale()).isEqualTo(2);
        assertThat(PayerCoverageRules.ceilingOf(null, "perInvoiceCeiling")).isNull();

        FieldBusinessException decimal = refusal(() ->
            PayerCoverageRules.ceilingOf(new BigDecimal("150000.50"), "annualCeilingPerBeneficiary"));
        assertThat(decimal.getField()).isEqualTo("annualCeilingPerBeneficiary");
        assertThat(decimal.getMessage()).isEqualTo("Saisissez le plafond en francs CFA entiers, sans décimales.");

        assertThat(refusal(() -> PayerCoverageRules.ceilingOf(BigDecimal.ZERO, "perActCeiling")).getField())
            .isEqualTo("perActCeiling");
        assertThat(refusal(() -> PayerCoverageRules.ceilingOf(new BigDecimal("-5"), "perActCeiling")).getField())
            .isEqualTo("perActCeiling");
        assertThat(refusal(() -> PayerCoverageRules.ceilingOf(new BigDecimal("10000000000"), "perInvoiceCeiling"))
            .getField()).isEqualTo("perInvoiceCeiling");
    }

    @Test
    void aRuleNamingBothACategoryAndAnItemIsRefusedOnTheItem() {
        FieldBusinessException refused = refusal(() ->
            PayerCoverageRules.requireOneTarget(PriceListCategory.LAB_TEST, UUID.randomUUID()));
        assertThat(refused.getField()).isEqualTo("priceListItemId");
        assertThat(refused.getMessage())
            .isEqualTo("Une règle porte sur une catégorie ou sur un article de la grille, pas les deux.");
    }

    @Test
    void aRuleNamingNoTargetIsRefusedOnTheCategory() {
        assertThat(refusal(() -> PayerCoverageRules.requireOneTarget(null, null)).getField()).isEqualTo("category");
        PayerCoverageRules.requireOneTarget(PriceListCategory.IMAGING, null);
        PayerCoverageRules.requireOneTarget(null, UUID.randomUUID());
    }

    @Test
    void anExclusionCarriesNoTermsAndAnyOtherRuleCarriesOne() {
        assertThat(refusal(() -> PayerCoverageRules.requireCoherentTerms(true, 50, null)).getField())
            .isEqualTo("coveragePercent");
        assertThat(refusal(() -> PayerCoverageRules.requireCoherentTerms(true, null, BigDecimal.TEN)).getField())
            .isEqualTo("perActCeiling");
        assertThat(refusal(() -> PayerCoverageRules.requireCoherentTerms(false, null, null)).getField())
            .isEqualTo("coveragePercent");
        PayerCoverageRules.requireCoherentTerms(true, null, null);
        PayerCoverageRules.requireCoherentTerms(false, 50, null);
        PayerCoverageRules.requireCoherentTerms(false, null, new BigDecimal("10000"));
    }

    @Test
    void aStartInThePastIsRefusedAndNoneMeansToday() {
        assertThat(PayerCoverageRules.startOf(null, TODAY)).isEqualTo(TODAY);
        assertThat(PayerCoverageRules.startOf(TODAY.plusDays(16), TODAY)).isEqualTo(TODAY.plusDays(16));
        assertThat(refusal(() -> PayerCoverageRules.startOf(TODAY.minusDays(1), TODAY)).getField())
            .isEqualTo("effectiveFrom");
    }

    private static PayerPlanRule rule(LocalDate from, LocalDate to) {
        return PayerPlanRule.builder().category(PriceListCategory.IMAGING).coveragePercent(50)
            .effectiveFrom(from).effectiveTo(to).build();
    }

    @Test
    void aNewRuleClosesTheOneInForceOnTheDayBefore() {
        PayerPlanRule inForce = rule(TODAY.minusDays(30), null);
        PayerPlanRule older = rule(TODAY.minusDays(90), TODAY.minusDays(31));

        PayerCoverageRules.RulePlan plan = PayerCoverageRules.plan(List.of(older, inForce), TODAY.plusDays(16), false);

        assertThat(plan.predecessor()).containsSame(inForce);
        assertThat(plan.toReplace()).isEmpty();
    }

    @Test
    void aPlannedRuleForTheSameTargetIsReplacedOnlyWhenConfirmed() {
        PayerPlanRule planned = rule(TODAY.plusDays(16), null);
        PayerPlanRule inForce = rule(TODAY.minusDays(10), TODAY.plusDays(15));

        FieldBusinessException question = refusal(() ->
            PayerCoverageRules.plan(List.of(inForce, planned), TODAY.plusDays(5), false));
        assertThat(question.getField()).isEqualTo("replaceScheduled");
        assertThat(question.getMessage()).contains("déc. 2026");

        PayerCoverageRules.RulePlan confirmed = PayerCoverageRules.plan(List.of(inForce, planned), TODAY.plusDays(5),
            true);
        assertThat(confirmed.toReplace()).containsExactly(planned);
        assertThat(confirmed.predecessor()).containsSame(inForce);
    }

    @Test
    void aCancelledRuleIsIgnored() {
        PayerPlanRule cancelled = rule(TODAY.plusDays(3), TODAY.plusDays(2));
        PayerCoverageRules.RulePlan plan = PayerCoverageRules.plan(List.of(cancelled), TODAY.plusDays(3), false);
        assertThat(plan.toReplace()).isEmpty();
        assertThat(plan.predecessor()).isEmpty();
    }

    @Test
    void theStatusFollowsTheDates() {
        assertThat(PayerCoverageRules.statusOf(rule(TODAY.plusDays(1), null), TODAY))
            .isEqualTo(PayerCoverageStatus.SCHEDULED);
        assertThat(PayerCoverageRules.statusOf(rule(TODAY, null), TODAY)).isEqualTo(PayerCoverageStatus.IN_FORCE);
        assertThat(PayerCoverageRules.statusOf(rule(TODAY.minusDays(9), TODAY.minusDays(1)), TODAY))
            .isEqualTo(PayerCoverageStatus.ENDED);
        assertThat(PayerCoverageRules.statusOf(rule(TODAY.plusDays(3), TODAY.plusDays(2)), TODAY))
            .isEqualTo(PayerCoverageStatus.CANCELLED);
        PayerPlan plan = PayerPlan.builder().effectiveFrom(TODAY).effectiveTo(TODAY.minusDays(1)).build();
        assertThat(PayerCoverageRules.statusOf(plan, TODAY)).isEqualTo(PayerCoverageStatus.CANCELLED);
        assertThat(plan.endedBefore(TODAY)).isTrue();
    }
}
