package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-01 and the disposition on catalogue limits. Illustrative limits only: the values are test data,
 * no reference table is seeded anywhere.
 */
@DisplayName("Paediatric limits: a limit judges its own route, and only an approved one clears a dose")
class PediatricDoseRouteLimitTest {

    private static final String CANNOT_ASSESS = "cannot assess, review required";
    private static final double SIX_MONTH_KG = 7d;

    private final PediatricDoseRule rule = new PediatricDoseRule();

    private static Patient childAgedMonths(int months) {
        Patient patient = Patient.builder()
            .dateOfBirth(LocalDate.now(ZoneId.systemDefault()).minusMonths(months).minusDays(1)).build();
        patient.setId(UUID.randomUUID());
        return patient;
    }

    private static MedicationCatalogItem limit(String route, PediatricDoseLimitStatus status) {
        return MedicationCatalogItem.builder().nameFr("Paracétamol").route(route)
            .pediatricMaxDoseMgPerKg(new BigDecimal("15")).pediatricMaxDailyMgPerKg(new BigDecimal("60"))
            .pediatricMaxDailyMg(new BigDecimal("1000"))
            .pediatricDoseSource("Hospital protocol").pediatricDoseStatus(status).build();
    }

    private List<CdsCard> evaluate(MedicationCatalogItem item, String dose, String route, String formulation) {
        return rule.evaluate(new CdsRuleContext(childAgedMonths(6), UUID.randomUUID(), "Paracétamol", null, null,
            CdsRuleEngine.parseDoseMg(dose, SIX_MONTH_KG, item), item, SIX_MONTH_KG, List.of(), List.of(),
            "3 fois par jour", CdsRuleEngine.parseDoseUnits(dose), LocalDateTime.now(ZoneId.systemDefault()), null,
            ParacetamolFacts.NONE).withRoute(route, formulation));
    }

    @Test
    void anApprovedLimitOfTheOrderedRouteClearsADoseUnderIt() {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "100 mg", "PO", null)).isEmpty();
    }

    @Test
    void aSixMonthOldOrderedIvAgainstAnOralLimitGetsNoVerdictButTheReview() {
        List<CdsCard> cards = evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "200 mg", "IV", null);

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.summary()).contains(CANNOT_ASSESS).contains("limits on file are for the route");
            assertThat(card.requiresReview()).isTrue();
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
        });
    }

    @Test
    void aCapRecordedForAnotherRouteIsNoHardStop() {
        // 900 mg x 3: above the oral item's 1000 mg, ordered IV.
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "900 mg", "IV", null))
            .noneMatch(CdsCard::blocksOverride);
    }

    @ParameterizedTest(name = "route [{0}]: cannot assess")
    @CsvSource(value = {"NULL", "BOGUS"}, nullValues = "NULL")
    void aMissingOrUnknownRouteGetsNoMgVerdict(String route) {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "200 mg", route, null))
            .singleElement().satisfies(card -> {
                assertThat(card.summary()).contains(CANNOT_ASSESS);
                assertThat(card.requiresReview()).isTrue();
            });
    }

    @Test
    void theAbsoluteCapStillRunsFirstWhenTheRouteIsNotKnownToBeAnother() {
        for (String route : new String[] {null, "BOGUS", "PO"}) {
            assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "900 mg", route, null))
                .first().satisfies(card -> assertThat(card.blocksOverride()).isTrue());
        }
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL), "900 mg", "PO", null))
            .first().satisfies(card -> assertThat(card.blocksOverride()).isTrue());
        assertThat(evaluate(limit(null, PediatricDoseLimitStatus.APPROVED), "900 mg", "IV", null))
            .first().satisfies(card -> assertThat(card.blocksOverride()).isTrue());
    }

    @Test
    void aDoseUnderALimitPendingApprovalIsNotClearedAndAskedToBeReviewed() {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL), "100 mg", "PO", null))
            .singleElement().satisfies(card -> {
                assertThat(card.summary()).contains(CANNOT_ASSESS).contains("not approved here").contains("not cleared");
                assertThat(card.requiresReview()).isTrue();
            });
    }

    @Test
    void aLimitWithNoStatusIsNotApproved() {
        assertThat(evaluate(limit("PO", null), "100 mg", "PO", null)).singleElement()
            .satisfies(card -> assertThat(card.requiresReview()).isTrue());
    }

    @Test
    void aDoseUnderALimitThatRecordsNoRouteIsNotCleared() {
        assertThat(evaluate(limit(null, PediatricDoseLimitStatus.APPROVED), "100 mg", "PO", null))
            .singleElement().satisfies(card -> {
                assertThat(card.summary()).contains(CANNOT_ASSESS).contains("does not record its route");
                assertThat(card.requiresReview()).isTrue();
            });
    }

    @Test
    void aDoseAboveAPendingLimitKeepsItsStopAndTheReview() {
        List<CdsCard> cards = evaluate(limit("PO", PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL), "200 mg", "PO", null);
        assertThat(cards).anyMatch(CdsCard::requiresReview)
            .anyMatch(card -> card.indicator() == CdsCard.Indicator.CRITICAL);
    }

    @Test
    void underTheManualReviewAgeTheReviewStaysAndTheCapRunsFirst() {
        MedicationCatalogItem item = limit("PO", PediatricDoseLimitStatus.APPROVED);
        Patient infant = childAgedMonths(2);
        assertThat(rule.evaluate(new CdsRuleContext(infant, UUID.randomUUID(), "Paracétamol", null, null, 30d, item, 4d,
                List.of(), List.of(), "3 fois par jour", null, LocalDateTime.now(ZoneId.systemDefault()), null,
                ParacetamolFacts.NONE).withRoute("PO", null)))
            .singleElement().satisfies(card -> {
                assertThat(card.requiresReview()).isTrue();
                assertThat(card.summary()).contains("child under 3 months");
            });
        assertThat(rule.evaluate(new CdsRuleContext(infant, UUID.randomUUID(), "Paracétamol", null, null, 900d, item, 4d,
                List.of(), List.of(), "3 fois par jour", null, LocalDateTime.now(ZoneId.systemDefault()), null,
                ParacetamolFacts.NONE).withRoute("PO", null)))
            .first().satisfies(card -> assertThat(card.blocksOverride()).isTrue());
    }

    @Test
    void aProductFormTheOrderedRouteCannotUseGetsNoVerdict() {
        MedicationCatalogItem item = limit("PR", PediatricDoseLimitStatus.APPROVED);
        item.setForm("suppositoire");
        // The form and the order disagree, the limit's route is rectal and so is the product: IV is judged on neither.
        assertThat(evaluate(item, "100 mg", "IV", "suppositoire")).singleElement()
            .satisfies(card -> assertThat(card.requiresReview()).isTrue());
        item.setRoute("IV");
        assertThat(evaluate(item, "100 mg", "IV", "suppositoire")).singleElement()
            .satisfies(card -> assertThat(card.summary()).contains("does not agree with the product form"));
    }

    @Test
    void aDrugWithNoLimitOnFileKeepsItsAdviceAndAsksForNoSecondPerson() {
        MedicationCatalogItem none = MedicationCatalogItem.builder().nameFr("Ceftriaxone").route("IV").build();
        assertThat(evaluate(none, "100 mg", null, null)).singleElement().satisfies(card -> {
            assertThat(card.summary()).contains("verify weight-based dosing");
            assertThat(card.requiresReview()).isFalse();
        });
    }

    @Test
    void paracetamolWithNoLimitOnFileIsReviewedAnyRoute() {
        ParacetamolFacts paracetamol = new ParacetamolFacts(true, List.of(), java.util.Set.of());
        for (String route : new String[] {"PO", "IV", null}) {
            assertThat(rule.evaluate(new CdsRuleContext(childAgedMonths(6), UUID.randomUUID(), "Paracétamol", null, null,
                    100d, null, SIX_MONTH_KG, List.of(), List.of(), "3 fois par jour", null,
                    LocalDateTime.now(ZoneId.systemDefault()), null, paracetamol).withRoute(route, null)))
                .singleElement().satisfies(card -> {
                    assertThat(card.summary()).contains(CANNOT_ASSESS).contains("no paracetamol limit");
                    assertThat(card.requiresReview()).isTrue();
                });
        }
    }

    @Test
    void aFailedReadOfTheFactsHoldsAChildsOrderToo() {
        assertThat(rule.evaluate(new CdsRuleContext(childAgedMonths(6), UUID.randomUUID(), "Paracétamol", null, null,
                100d, null, SIX_MONTH_KG, List.of(), List.of(), "3 fois par jour", null, null, null,
                ParacetamolFacts.READ_FAILED).withRoute("PO", null)))
            .singleElement().satisfies(card -> assertThat(card.needsReasonAtSignature()).isTrue());
    }

    /** The urgent exception (provisional) reaches the cannot-assess cards, and only those. */
    @Test
    void cannotAssessCardsCarryAKeyAndTheOtherReviewsDoNot() {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "200 mg", "IV", null))
            .singleElement().satisfies(card -> assertThat(card.exceptionKey()).startsWith("paedLimit:"));
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL), "100 mg", "PO", null))
            .singleElement().satisfies(card -> assertThat(card.exceptionKey()).startsWith("paedLimit:"));
        MedicationCatalogItem item = limit("PO", PediatricDoseLimitStatus.APPROVED);
        assertThat(rule.evaluate(new CdsRuleContext(childAgedMonths(2), UUID.randomUUID(), "Paracétamol", null, null, 30d,
                item, 4d, List.of(), List.of(), "3 fois par jour", null, LocalDateTime.now(ZoneId.systemDefault()), null,
                ParacetamolFacts.NONE).withRoute("PO", null)))
            .singleElement().satisfies(card -> {
                assertThat(card.requiresReview()).isTrue();
                assertThat(card.exceptionKey()).as("the review by age has no urgent exception").isNull();
            });
    }

    @Test
    void aDoseAboveAnApprovedLimitAsksForTheDecisionAtTheSignatureAndIsNoHardStop() {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.APPROVED), "200 mg", "PO", null))
            .isNotEmpty().allSatisfy(card -> {
                assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
                assertThat(card.needsReasonAtSignature()).isTrue();
                assertThat(card.exceptionKey()).startsWith("paedAbove:");
                assertThat(card.blocksOverride()).isFalse();
                assertThat(card.requiresReview()).isFalse();
            });
    }

    /** A limit that is not established raises no decision by itself: the stop as it was, and the review. */
    @Test
    void aLimitNotEstablishedRaisesNoDecisionAtTheSignature() {
        assertThat(evaluate(limit("PO", PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL), "200 mg", "PO", null))
            .noneMatch(CdsCard::needsReasonAtSignature);
        assertThat(evaluate(limit(null, PediatricDoseLimitStatus.APPROVED), "200 mg", "PO", null))
            .noneMatch(CdsCard::needsReasonAtSignature);
    }
}
