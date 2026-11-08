package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-01, CR-02 and CR-03 on the adult paracetamol rule: the oral limits judge an oral order and no
 * other; a finding outside them holds the routine signature, tied to the finding; and the documented
 * doses are a figure of their own, whose unknown part is never read as zero.
 */
@DisplayName("Adult paracetamol: route, held findings and documented exposure")
class AdultParacetamolRouteExposureTest {

    private static final String CANNOT_ASSESS = "cannot assess, review required";
    private final AdultParacetamolRule rule = new AdultParacetamolRule();

    private static Patient adult() {
        Patient patient = Patient.builder().dateOfBirth(LocalDate.now(ZoneId.systemDefault()).minusYears(40)).build();
        patient.setId(UUID.randomUUID());
        return patient;
    }

    private static MedicationCatalogItem product(String route, String form) {
        return MedicationCatalogItem.builder().nameFr("Paracétamol").genericName("Paracetamol")
            .strength("500").strengthUnit("mg").route(route).form(form).build();
    }

    private static ParacetamolFacts facts(ParacetamolFacts.Documented documented) {
        return new ParacetamolFacts(true, List.of(), Set.of(), List.of(), List.of(), false, false, false, null,
            List.of(), false, documented);
    }

    private static ParacetamolFacts.Documented doses(ParacetamolFacts.Dose... doses) {
        return new ParacetamolFacts.Documented(true, List.of(doses), List.of());
    }

    /** One clock per test: two doses read the clock apart, so a slow runner put 1.98 h between "2 h" doses. */
    private static LocalDateTime base;

    @org.junit.jupiter.api.BeforeEach
    void readTheClockOnce() {
        base = LocalDateTime.now(ZoneId.systemDefault());
    }

    private static ParacetamolFacts.Dose given(String dose, int hoursAgo) {
        return new ParacetamolFacts.Dose(base.minusHours(hoursAgo),
            "Paracétamol", dose, null);
    }

    private static CdsRuleContext order(String dose, String frequency, String route, MedicationCatalogItem item,
                                        ParacetamolFacts facts) {
        return new CdsRuleContext(adult(), UUID.randomUUID(), "Paracétamol", null, null,
            CdsRuleEngine.parseDoseMg(dose, 70d, item), item, 70d, List.of(), List.of(), frequency,
            CdsRuleEngine.parseDoseUnits(dose), null, null, facts)
            .withRoute(route, item == null ? null : item.getForm());
    }

    private List<CdsCard> evaluate(String dose, String frequency, String route) {
        return rule.evaluate(order(dose, frequency, route, null, facts(ParacetamolFacts.Documented.NOT_READ)));
    }

    /* ---- CR-01: the route matrix ---- */

    @ParameterizedTest(name = "route {0}: no oral verdict")
    @CsvSource({"IV", "Intraveineuse (IV)", "INTRAVENOUS", "PR", "rectale", "IM", "SL", "TOP", "BOGUS", "NG tube"})
    void anyRouteButOralGetsCannotAssessAndNoOralVerdict(String route) {
        List<CdsCard> cards = evaluate("2 g", "toutes les 2 heures", route);

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.summary()).contains(CANNOT_ASSESS);
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(card.needsReasonAtSignature()).isTrue();
            assertThat(card.exceptionKey()).startsWith("route:");
            assertThat(card.summary())
                .doesNotContain("per administration").doesNotContain("is above the adult maximum");
            assertThat(card.detail()).contains("No limit was judged").contains("not applied")
                .doesNotContain("Judged against").doesNotContain("Daily limit");
        });
    }

    @Test
    void noRouteAtAllIsCannotAssessNeverAnImpliedOralOrder() {
        assertThat(evaluate("500 mg", "3 fois par jour", null)).singleElement()
            .satisfies(card -> assertThat(card.summary()).contains(CANNOT_ASSESS).contains("states no route"));
        assertThat(evaluate("500 mg", "3 fois par jour", "  ")).singleElement()
            .satisfies(card -> assertThat(card.summary()).contains(CANNOT_ASSESS));
    }

    @Test
    void theCatalogueRouteDoesNotStandInForTheOrderedOne() {
        ParacetamolFacts none = facts(ParacetamolFacts.Documented.NOT_READ);
        // An oral product and no route on the order: still no route.
        assertThat(rule.evaluate(order("1 g", "3 fois par jour", null, product("PO", "comprimé"), none)))
            .singleElement().satisfies(card -> assertThat(card.summary()).contains(CANNOT_ASSESS));
        // An oral product ordered IV is judged as IV, not as its product.
        assertThat(rule.evaluate(order("1 g", "3 fois par jour", "IV", product("PO", "comprimé"), none)))
            .singleElement().satisfies(card -> assertThat(card.summary()).contains(CANNOT_ASSESS));
    }

    @ParameterizedTest(name = "ordered PO, product route {0}, form {1}: the two disagree")
    @CsvSource({"IV,", ",suppositoire", ",Solution pour perfusion", "PR,suppository", "Intraveineuse,injectable"})
    void anOralOrderOfAProductThatIsNotOralIsNotJudgedByTheOralLimits(String route, String form) {
        assertThat(rule.evaluate(order("2 g", "3 fois par jour", "PO", product(route, form),
                facts(ParacetamolFacts.Documented.NOT_READ))))
            .singleElement().satisfies(card -> assertThat(card.summary()).contains(CANNOT_ASSESS).contains("do not agree"));
    }

    @Test
    void anOralOrderOfAnOralProductIsJudged() {
        assertThat(rule.evaluate(order("1,5 g", "3 fois par jour", "ORAL", product("Orale", "comprimé"),
                facts(ParacetamolFacts.Documented.NOT_READ))))
            .anySatisfy(card -> assertThat(card.summary()).contains("per administration"));
    }

    @Test
    void aDoseInMillilitresIsNeverReadAsMgAndIsSaidNotPassedInSilence() {
        assertThat(evaluate("10 ml", "3 fois par jour", "PO")).singleElement()
            .satisfies(card -> {
                assertThat(card.summary()).contains("could not be read in mg");
                assertThat(card.needsReasonAtSignature()).isFalse();
            });
    }

    /* ---- CR-02: which findings hold the signature ---- */

    @Test
    void aSingleDoseADailyTotalAndAnIntervalOutsideTheLimitsEachHoldTheSignature() {
        List<CdsCard> cards = evaluate("1,5 g", "toutes les 2 heures", "PO");

        assertThat(cards).hasSize(3).allSatisfy(card -> {
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(card.needsReasonAtSignature()).isTrue();
            assertThat(card.blocksOverride()).isFalse();
            assertThat(card.requiresReview()).isFalse();
            assertThat(card.detail()).contains("explicit exception").contains("does not grant")
                .doesNotContain("does not block");
        });
        assertThat(cards).extracting(CdsCard::exceptionKey)
            .anySatisfy(key -> assertThat(key).startsWith("single:"))
            .anySatisfy(key -> assertThat(key).startsWith("daily:"))
            .anySatisfy(key -> assertThat(key).startsWith("interval:"));
    }

    @Test
    void withinTheLimitsNothingIsHeld() {
        assertThat(evaluate("1 g", "4 fois par jour", "PO")).noneMatch(CdsCard::needsReasonAtSignature);
        assertThat(evaluate("500 mg", "3 fois par jour", "PO")).noneMatch(CdsCard::needsReasonAtSignature);
    }

    @Test
    void aChangeOfTheFindingChangesItsKey() {
        String oneAndAHalf = evaluate("1,5 g", "1 fois par jour", "PO").get(0).exceptionKey();
        String two = evaluate("2 g", "1 fois par jour", "PO").get(0).exceptionKey();
        assertThat(oneAndAHalf).isNotEqualTo(two);
    }

    /* ---- CR-03: two figures, and the unknown ---- */

    @Test
    void theTwoFiguresAreLabelledApartAndNeverAdded() {
        List<CdsCard> cards = rule.evaluate(order("1,5 g", "1 fois par jour", "PO", null,
            facts(doses(given("500 mg", 2), given("500 mg", 8)))));

        assertThat(cards.get(0).detail())
            .contains("Estimated scheduled exposure: 1500 mg per 24 h")
            .contains("Documented administered exposure: 1000 mg recorded as given in the last 24 hours")
            .contains("(2 dose(s)")
            .contains("including orders that have ended")
            .doesNotContain("2500 mg");
    }

    @Test
    void dosesOfAnEndedOrderAreOnRecordWhenNoOrderCountsThem() {
        // No other order runs: only the record shows 3.5 g given.
        List<CdsCard> cards = rule.evaluate(order("1 g", "1 fois par jour", "PO", null,
            facts(doses(given("1 g", 1), given("1 g", 6), given("1 g", 11), given("500 mg", 16)))));

        assertThat(cards).anySatisfy(card -> {
            assertThat(card.summary()).contains("3500 mg of paracetamol recorded as given")
                .contains("that is 4500 mg").contains("above the maximum of 4000 mg");
            assertThat(card.needsReasonAtSignature()).isTrue();
            assertThat(card.exceptionKey()).startsWith("documented:");
        });
    }

    @Test
    void aDoseGivenChangesTheFindingsKey() {
        String before = rule.evaluate(order("1 g", "1 fois par jour", "PO", null,
            facts(doses(given("2 g", 1), given("2 g", 6))))).get(0).exceptionKey();
        String after = rule.evaluate(order("1 g", "1 fois par jour", "PO", null,
            facts(doses(given("2 g", 1), given("2 g", 6), given("1 g", 0))))).get(0).exceptionKey();
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void whatCannotBeReadIsUnknownNeverZero() {
        ParacetamolFacts.Documented documented = new ParacetamolFacts.Documented(true,
            List.of(given("2", 3), given("500 mg", 4)), List.of("Codoliprane", "Brand X"));
        CdsCard card = rule.evaluate(order("1,5 g", "1 fois par jour", "PO", null, facts(documented))).get(0);

        assertThat(card.detail())
            .contains("500 mg recorded as given")
            .contains("(1 dose(s)")
            .contains("cannot be read, so not counted: Codoliprane, Brand X, Paracétamol")
            .contains("Unknown, not zero: doses before a transfer, at another facility or taken outside the hospital");
    }

    @Test
    void aRecordThatCouldNotBeReadIsUnknownNotZero() {
        assertThat(evaluate("1,5 g", "1 fois par jour", "PO").get(0).detail())
            .contains("Documented administered exposure could not be read")
            .contains("Unknown, not zero")
            .doesNotContain("0 mg recorded");
    }

    /* ---- CR-03: at the bedside ---- */

    @Test
    void atTheDoseTheTotalIsAWarningAndHoldsNothing() {
        List<CdsCard> cards = rule.atAdministration(order("1 g", "4 fois par jour", "PO", null,
            facts(doses(given("1 g", 5), given("1 g", 10), given("1 g", 15), given("500 mg", 20)))), false);

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.summary()).contains("3500 mg recorded as given").contains("one more dose makes 4500 mg");
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(card.needsReasonAtSignature()).isFalse();
            assertThat(card.blocksOverride()).isFalse();
            assertThat(card.exceptionKey()).isNull();
            assertThat(card.detail()).contains("does not refuse the dose").contains("Unknown, not zero");
        });
    }

    @Test
    void atTheDoseADoseOfAnotherOrderTooSoonIsSaid() {
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "PO", null, facts(doses(given("500 mg", 2)))), false))
            .singleElement().satisfies(card -> assertThat(card.summary())
                .contains("from any order, was 2 h ago").contains("minimum between doses is 4 h"));
    }

    @Test
    void atAnIvDoseTheFigureIsShownAndNoOralLimitIsJudged() {
        assertThat(rule.atAdministration(order("1 g", "4 fois par jour", "IV", null,
                facts(doses(given("2 g", 1), given("2 g", 1)))), false))
            .singleElement().satisfies(card -> assertThat(card.summary())
                .contains("no paracetamol limit judged").contains("4000 mg recorded as given")
                .doesNotContain("above the maximum"));
    }

    @Test
    void atTheDoseNothingOnRecordAndNoUnknownSaysNothingAndAChildIsLeftToThePaediatricRule() {
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "PO", null, facts(doses())), false)).isEmpty();
        Patient child = Patient.builder().dateOfBirth(LocalDate.now(ZoneId.systemDefault()).minusYears(6)).build();
        assertThat(rule.atAdministration(new CdsRuleContext(child, UUID.randomUUID(), "Paracétamol", null, null, 500d,
            null, 20d, List.of(), List.of(), "4 fois par jour", null, null, null, facts(doses(given("1 g", 1)))), false))
            .isEmpty();
    }

    /** The review of this wave: the dose just written is on record, and it is not "0 h ago". */
    @Test
    void afterARecordedDoseTheIntervalIsToThePreviousDoseNotToItself() {
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "PO", null,
                facts(doses(given("500 mg", 0), given("500 mg", 6)))), true))
            .noneMatch(card -> card.summary().contains("h after the previous dose"))
            .noneMatch(card -> card.summary().contains("h ago"));
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "PO", null,
                facts(doses(given("500 mg", 0), given("500 mg", 2)))), true))
            .anySatisfy(card -> assertThat(card.summary()).contains("recorded 2 h after the previous dose"));
    }

    @Test
    void atTheDoseWhatCouldNotBeReadIsNeverAZeroInTheSummary() {
        ParacetamolFacts.Documented combination =
            new ParacetamolFacts.Documented(true, List.of(), List.of("Codoliprane"));
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "PO", null, facts(combination)), false))
            .singleElement().satisfies(card -> assertThat(card.summary()).contains("at least 0 mg"));
        assertThat(rule.atAdministration(order("500 mg", "4 fois par jour", "IV", null,
                facts(ParacetamolFacts.Documented.NOT_READ)), false))
            .singleElement().satisfies(card -> assertThat(card.summary())
                .contains("an unknown amount").doesNotContain("0 mg"));
    }

    @Test
    void atTheDoseAnUnreadNextDoseIsSaid() {
        assertThat(rule.atAdministration(order("10 ml", "4 fois par jour", "PO", null,
                facts(doses(given("500 mg", 6)))), false))
            .anySatisfy(card -> assertThat(card.summary()).contains("could not be read in mg")
                .contains("total after it is unknown"));
    }

    /** A failed read is no clean check: held, with a key, whatever the drug. */
    @Test
    void aFailedReadOfTheFactsHoldsTheSignature() {
        assertThat(rule.evaluate(new CdsRuleContext(adult(), UUID.randomUUID(), "Paracétamol", null, null, 500d, null,
                70d, List.of(), List.of(), "3 fois par jour", null, null, null, ParacetamolFacts.READ_FAILED)
                .withRoute("PO", null)))
            .singleElement().satisfies(card -> {
                assertThat(card.needsReasonAtSignature()).isTrue();
                assertThat(card.exceptionKey()).startsWith("checkFailed:");
            });
    }
}
