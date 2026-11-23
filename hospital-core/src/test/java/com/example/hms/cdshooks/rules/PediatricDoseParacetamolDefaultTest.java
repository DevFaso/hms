package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3306 (r70): paracetamol 500 mg four times a day for a 2-year-old of 12 kg (42 mg/kg per dose, 167 mg/kg per
 * day) got only « évaluation impossible … aucune limite de paracétamol enregistrée … source non précisée »,
 * although the weight was on the chart. The registered default (15 mg/kg per dose, 60 mg/kg per day by mouth)
 * now judges it with the latest weight on the chart, and a weight older than 30 days asks to be confirmed.
 */
class PediatricDoseParacetamolDefaultTest {

    private static final ParacetamolFacts ALONE = new ParacetamolFacts(true, List.of(), Set.of(), List.of(), List.of());
    private static final ParacetamolFacts COMBINATION =
        new ParacetamolFacts(true, List.of(), Set.of(), List.of(), List.of(), true, false, false);

    private final PediatricDoseRule rule = new PediatricDoseRule();

    private static Patient childAged(int years) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(years).minusDays(10));
        return p;
    }

    /** The dev catalogue row of the QA run: paracetamol 500 mg tablets, no paediatric limit of its own. */
    private static MedicationCatalogItem paracetamolWithNoLimit() {
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("Paracétamol 500 mg").genericName("Paracétamol").atcCode("N02BE01")
            .strength("500").strengthUnit("mg").route("PO").form("comprimé").build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static CdsRuleContext ctx(MedicationCatalogItem item, String dosage, String frequency, Double weightKg,
                                      LocalDateTime weighedAt, ParacetamolFacts facts, String route) {
        return new CdsRuleContext(childAged(2), UUID.randomUUID(), "Paracétamol 500 mg", null, null,
            CdsRuleEngine.parseDoseMg(dosage, weightKg, item), item, weightKg, List.of(), List.of(),
            frequency, CdsRuleEngine.parseDoseUnits(dosage), weighedAt, null, facts).withRoute(route, null);
    }

    private static long critical(List<CdsCard> cards) {
        return cards.stream().filter(c -> c.indicator() == CdsCard.Indicator.CRITICAL).count();
    }

    @Test
    void d3306_theOverdoseOfTheReportIsStoppedWithItsFiguresAndTheWeightOnTheChart() {
        List<CdsCard> cards = rule.evaluate(ctx(paracetamolWithNoLimit(), "500 mg", "4 fois par jour", 12.0,
            LocalDateTime.now().minusHours(2), ALONE, "PO"));

        // 41.67 mg/kg per dose against 15, and 166.67 mg/kg per day against 60.
        assertThat(critical(cards)).isEqualTo(2);
        assertThat(cards)
            .anySatisfy(c -> assertThat(c.summary()).contains("41.67 mg/kg").contains("15 mg/kg"))
            .anySatisfy(c -> assertThat(c.summary()).contains("166.67 mg/kg").contains("60 mg/kg"))
            .anySatisfy(c -> {
                assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.INFO);
                assertThat(c.summary()).contains("weight on the chart 12 kg").contains("recorded on")
                    .contains("41.67 mg/kg per dose").contains("166.67 mg/kg per day").contains("provisional");
            })
            // The old answer is gone: there IS a limit, and it names its source.
            .noneSatisfy(c -> assertThat(c.summary()).contains("no paracetamol limit on file"))
            .allSatisfy(c -> assertThat(c.detail()).contains("MSF").doesNotContain("source not stated"));
    }

    @Test
    void aDoseUnderTheDefaultShowsItsFiguresAndIsStillHeldForReview() {
        // 180 mg four times a day at 12 kg: 15 mg/kg per dose, 60 mg/kg per day.
        List<CdsCard> cards = rule.evaluate(ctx(paracetamolWithNoLimit(), "180 mg", "4 fois par jour", 12.0,
            LocalDateTime.now(), ALONE, "PO"));

        assertThat(critical(cards)).isZero();
        assertThat(cards)
            .anySatisfy(c -> {
                assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.INFO);
                assertThat(c.summary()).contains("15 mg/kg per dose").contains("60 mg/kg per day");
            })
            // A published default pending local approval clears nothing by itself.
            .anySatisfy(c -> {
                assertThat(c.requiresReview()).isTrue();
                assertThat(c.summary()).contains("default paediatric paracetamol limits").contains("provisional");
            });
    }

    @Test
    void aWeightOlderThanThirtyDaysAsksToBeConfirmed() {
        LocalDateTime weighedAt = LocalDateTime.now().minusDays(45);
        List<CdsCard> stale = rule.evaluate(ctx(paracetamolWithNoLimit(), "180 mg", "4 fois par jour", 12.0,
            weighedAt, ALONE, "PO"));
        List<CdsCard> fresh = rule.evaluate(ctx(paracetamolWithNoLimit(), "180 mg", "4 fois par jour", 12.0,
            LocalDateTime.now().minusDays(PediatricDoseRule.WEIGHT_MAX_AGE_DAYS), ALONE, "PO"));

        assertThat(stale).anySatisfy(c -> {
            assertThat(c.summary()).contains("45 days ago").contains("confirm that this weight still holds");
            // Held, never a plain advisory: the review, or the prescriber's explicit exception with a reason.
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.exceptionKey()).isEqualTo("paedWeight:" + weighedAt.toLocalDate());
        });
        assertThat(fresh).noneSatisfy(c -> assertThat(c.summary()).contains("days ago"));
    }

    @Test
    void anApprovedLimitWithAnOldWeightIsHeldToo() {
        MedicationCatalogItem own = paracetamolWithNoLimit();
        own.setPediatricMaxDoseMgPerKg(new BigDecimal("15"));
        own.setPediatricDoseSource("Formulaire local");
        own.setPediatricDoseStatus(PediatricDoseLimitStatus.APPROVED);

        assertThat(rule.evaluate(ctx(own, "180 mg", "4 fois par jour", 12.0, LocalDateTime.now().minusDays(31), ALONE, "PO")))
            .singleElement().satisfies(c -> {
                assertThat(c.requiresReview()).isTrue();
                assertThat(c.exceptionKey()).startsWith("paedWeight:");
            });
        // With a recent weight the approved limit clears the dose, and no default card is added to it.
        assertThat(rule.evaluate(ctx(own, "180 mg", "4 fois par jour", 12.0, LocalDateTime.now(), ALONE, "PO"))).isEmpty();
    }

    @Test
    void withNoWeightOnTheChartTheOrderIsHeldAndSaysSo() {
        List<CdsCard> cards = rule.evaluate(ctx(paracetamolWithNoLimit(), "500 mg", "4 fois par jour", null, null, ALONE, "PO"));

        assertThat(cards).anySatisfy(c -> assertThat(c.requiresReview()).isTrue())
            .anySatisfy(c -> assertThat(c.detail()).contains("weight not on file"));
    }

    @Test
    void theDefaultIsForTheOralRouteOnly() {
        List<CdsCard> cards = rule.evaluate(ctx(paracetamolWithNoLimit(), "500 mg", "4 fois par jour", 12.0,
            LocalDateTime.now(), ALONE, "IV"));

        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.summary()).contains("cannot assess");
        });
    }

    @Test
    void aCombinationIsNotJudgedByTheDefault() {
        List<CdsCard> cards = rule.evaluate(ctx(paracetamolWithNoLimit(), "500 mg", "4 fois par jour", 12.0,
            LocalDateTime.now(), COMBINATION, "PO"));

        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.summary()).contains("no paracetamol limit on file");
        });
    }

    @Test
    void underOneMonthTheDefaultIsTenAndFortyAndTheReviewByAgeStillApplies() {
        MedicationCatalogItem neonate = ParacetamolPaediatricDefault.asDefaultLimit(paracetamolWithNoLimit(), 0);
        MedicationCatalogItem infant = ParacetamolPaediatricDefault.asDefaultLimit(null, 1);

        assertThat(neonate.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("10");
        assertThat(neonate.getPediatricMaxDailyMgPerKg()).isEqualByComparingTo("40");
        assertThat(infant.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
        assertThat(infant.getPediatricMaxDailyMgPerKg()).isEqualByComparingTo("60");
        assertThat(infant.getPediatricDoseStatus()).isEqualTo(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL);
        assertThat(infant.getPediatricMaxDailyMg()).isNull();
    }
}
