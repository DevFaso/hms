package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.impl.PrnMinInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adult paracetamol alerts, boundary by boundary. Values: FDA acetaminophen guidance and
 * EFFERALGANSTIX 1000 mg RCP 4.2 and 4.3, user-supplied 2026-11-07, as {@link AdultParacetamolRule}
 * cites them.
 */
class AdultParacetamolRuleTest {

    private static final String SINGLE = "per administration";
    private static final String DAILY = "is above the adult maximum";
    private static final String INTERVAL = "between doses";
    private static final String INFO = "The usual adult dose";
    private static final String CONTRA = "is contraindicated";
    private static final String UNREAD = "could not be read from the frequency";

    private final AdultParacetamolRule rule = new AdultParacetamolRule();

    private static Patient agedYears(int years) {
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.now().minusYears(years));
        return patient;
    }

    private static MedicationCatalogItem tablet500() {
        return MedicationCatalogItem.builder().nameFr("Paracétamol").genericName("Paracetamol")
            .strength("500").strengthUnit("mg").build();
    }

    private static Double prn(Integer hours) {
        return PrnMinInterval.highestDosesPerDay(hours != null, hours);
    }

    private List<CdsCard> evaluate(Order order) {
        Double weight = order.weight;
        Double doseMg = CdsRuleEngine.parseDoseMg(order.dose, weight, order.item);
        return rule.evaluate(new CdsRuleContext(order.patient, UUID.randomUUID(), "Paracétamol", null, null, doseMg,
            order.item, weight, List.of(), List.of(), order.frequency, CdsRuleEngine.parseDoseUnits(order.dose),
            order.weighedAt, prn(order.prnHours),
            new ParacetamolFacts(true, order.others, order.risks)));
    }

    /** One proposed order and what the rule reads around it. */
    private static final class Order {
        private final String dose;
        private final String frequency;
        private Patient patient = agedYears(40);
        private MedicationCatalogItem item = tablet500();
        private Double weight = 70d;
        private LocalDateTime weighedAt;
        private Integer prnHours;
        private List<ParacetamolFacts.OtherOrder> others = List.of();
        private Set<ParacetamolRisk> risks = Set.of();

        Order(String dose, String frequency) {
            this.dose = dose;
            this.frequency = frequency;
        }

        Order prn(Integer hours) {
            prnHours = hours;
            return this;
        }

        Order weight(Double kg) {
            weight = kg;
            return this;
        }

        Order risks(ParacetamolRisk... groups) {
            risks = groups.length == 0 ? Set.of() : EnumSet.of(groups[0], groups);
            return this;
        }

        Order others(ParacetamolFacts.OtherOrder... orders) {
            others = List.of(orders);
            return this;
        }
    }

    private static ParacetamolFacts.OtherOrder other(String dose, String frequency) {
        return new ParacetamolFacts.OtherOrder(
            Prescription.builder().medicationName("Doliprane").dosage(dose).frequency(frequency).build(), tablet500());
    }

    /** The alerts by letter: S single, D daily, I interval, N information, U unread, C contraindication. */
    private static String letters(List<CdsCard> cards) {
        Set<String> out = new TreeSet<>();
        for (CdsCard card : cards) {
            String summary = card.summary();
            if (summary.contains(SINGLE)) out.add("S");
            else if (summary.contains(DAILY)) out.add("D");
            else if (summary.contains(INFO)) out.add("N");
            else if (summary.contains(UNREAD)) out.add("U");
            else if (summary.contains(CONTRA)) out.add("C");
            else if (summary.contains(INTERVAL)) out.add("I");
            else out.add("?");
        }
        return String.join("", out);
    }

    @ParameterizedTest(name = "{0} / {1} / PRN {2}: [{3}]")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        // The brief's four cases.
        "2 000 mg | 3 fois par jour | null | DS",
        "1 g | 3 fois par jour | null | ''",
        "1 g | 4 fois par jour | null | N",
        "1 g | null | 1 | DI",
        // Per administration: at the limit, just above, every way of writing it.
        "1000 mg | null | null | ''",
        "1 000 mg | null | null | ''",
        "1,000 mg | null | null | ''",
        "1.000 mg | null | null | ''",
        "1001 mg | null | null | S",
        "1,1 g | null | null | S",
        // High end of a range; mixed numbers and tablets through the catalogue strength.
        "500-1000 mg | null | null | ''",
        "1-2 g | null | null | S",
        "500 mg à 1,5 g | null | null | S",
        "2 cp | null | null | ''",
        "3 cp | null | null | S",
        "2 1/2 cp | null | null | S",
        "1 cp et demi | null | null | ''",
        // The daily ceiling at, under and over it.
        "666 mg | 6 fois par jour | null | N",
        "667 mg | 6 fois par jour | null | D",
        "1 g | 5 fois par jour | null | D",
        "1 g | toutes les 4 heures | null | D",
        // 3 g/day is no information; above it is.
        "750 mg | 4 fois par jour | null | ''",
        "751 mg | 4 fois par jour | null | N",
        // The interval: exactly 4 hours passes, shorter does not, also when the total is fine.
        "500 mg | 6 fois par jour | null | ''",
        "500 mg | toutes les 4 heures | null | ''",
        "500 mg | 7 fois par jour | null | IN",
        "250 mg | toutes les 3 heures | null | I",
        "1 g | H0 H2 H4 | null | I",
        // A ticked as-needed order: 24 / interval, and its interval.
        "500 mg | si besoin | 4 | ''",
        "500 mg | si besoin | 3 | IN",
        "1 g | si besoin | 6 | N",
        "1 g | si besoin | 5 | D",
        "1 g | si besoin | 4 | D",
        "1 g | si besoin | 3 | DI",
        "1 g | si besoin | 72 | ''",
        // A frequency nobody can read: says so, and still judges the dose it knows.
        "1 g | selon la douleur | null | U",
        "2 g | selon la douleur | null | SU",
        "5 g | selon la douleur | null | DS",
        // A dose that is not mg is silent, never read as mg; the interval needs no dose.
        "5 ml | 3 fois par jour | null | ''",
        "5 ml | toutes les 2 heures | null | I",
    })
    void generalAdultLimits(String dose, String frequency, Integer prnHours, String expected) {
        assertThat(letters(evaluate(new Order(dose, frequency).prn(prnHours)))).isEqualTo(expected);
    }

    @Test
    void aCountOfTabletsWithNoStrengthIsSilent() {
        Order order = new Order("20 cp", "3 fois par jour");
        order.item = null;
        assertThat(evaluate(order)).isEmpty();
    }

    @Test
    void aCombinedStrengthIsNotReadAsMg() {
        Order order = new Order("20 cp", "3 fois par jour");
        order.item = MedicationCatalogItem.builder().genericName("Paracetamol").strength("500/30").strengthUnit("mg").build();
        assertThat(evaluate(order)).isEmpty();
    }

    @ParameterizedTest(name = "{0} kg, {1} {2}: [{3}] ceiling {4}")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        "null | 1 g | 4 fois par jour | N | null",
        "null | 1 g | 5 fois par jour | D | 4000",
        "50 | 1 g | 4 fois par jour | N | null",
        "50 | 1 g | 5 fois par jour | D | 4000",
        // 49.9 kg is under 50: 2994 mg/day. 45 kg gives 2,700.
        "49.9 | 1 g | 3 fois par jour | D | 2994",
        "49.9 | 998 mg | 3 fois par jour | '' | null",
        "45 | 900 mg | 3 fois par jour | '' | null",
        "45 | 901 mg | 3 fois par jour | D | 2700",
    })
    void weightOnFile(Double kg, String dose, String frequency, String expected, String ceiling) {
        List<CdsCard> cards = evaluate(new Order(dose, frequency).weight(kg));
        assertThat(letters(cards)).isEqualTo(expected);
        if (ceiling != null) {
            assertThat(cards.get(0).summary()).contains("maximum of " + ceiling + " mg per day");
        }
    }

    @ParameterizedTest(name = "{0} {1} kg {2} {3}: [{4}] ceiling {5}")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        // Malnutrition or dehydration: 60 mg/kg/day or 3,000 mg/day, the lower.
        "MALNUTRITION_OR_DEHYDRATION | null | 1 g | 3 fois par jour | '' | null",
        "MALNUTRITION_OR_DEHYDRATION | null | 751 mg | 4 fois par jour | D | 3000",
        "MALNUTRITION_OR_DEHYDRATION | 70 | 1 g | 3 fois par jour | '' | null",
        "MALNUTRITION_OR_DEHYDRATION | 70 | 751 mg | 4 fois par jour | D | 3000",
        "MALNUTRITION_OR_DEHYDRATION | 50 | 751 mg | 4 fois par jour | D | 3000",
        "MALNUTRITION_OR_DEHYDRATION | 45 | 901 mg | 3 fois par jour | D | 2700",
        // Hepatic, Gilbert or alcoholism: 2,000 mg/day.
        "TWO_GRAM_GROUP | 70 | 500 mg | 4 fois par jour | '' | null",
        "TWO_GRAM_GROUP | 70 | 501 mg | 4 fois par jour | D | 2000",
        "TWO_GRAM_GROUP | null | 501 mg | 4 fois par jour | D | 2000",
        // Several apply: the lowest. 30 kg is 1,800, below the 2,000; 45 kg is 2,700, above it.
        "TWO_GRAM_GROUP | 30 | 450 mg | 4 fois par jour | '' | null",
        "TWO_GRAM_GROUP | 30 | 451 mg | 4 fois par jour | D | 1800",
        "TWO_GRAM_GROUP | 45 | 501 mg | 4 fois par jour | D | 2000",
    })
    void loweredCeilings(ParacetamolRisk risk, Double kg, String dose, String frequency, String expected, String ceiling) {
        List<CdsCard> cards = evaluate(new Order(dose, frequency).weight(kg).risks(risk));
        assertThat(letters(cards)).isEqualTo(expected);
        if (ceiling != null) {
            assertThat(cards.get(0).summary()).contains("maximum of " + ceiling + " mg per day");
        }
    }

    @Test
    void theLowestCeilingWinsAcrossTheGroups() {
        List<CdsCard> cards = evaluate(new Order("501 mg", "4 fois par jour").weight(70d)
            .risks(ParacetamolRisk.MALNUTRITION_OR_DEHYDRATION, ParacetamolRisk.TWO_GRAM_GROUP));
        assertThat(cards.get(0).summary()).contains("maximum of 2000 mg per day");
    }

    @ParameterizedTest(name = "alcoholism, {0} PRN {1}: [{2}]")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        "250 mg | 3 fois par jour | null | ''",
        "250 mg | toutes les 8 heures | null | ''",
        "250 mg | toutes les 6 heures | null | I",
        "250 mg | 4 fois par jour | null | I",
        "250 mg | si besoin | 8 | ''",
        "250 mg | si besoin | 7 | I",
    })
    void chronicAlcoholismNeedsEightHours(String dose, String frequency, Integer prnHours, String expected) {
        List<CdsCard> cards = evaluate(new Order(dose, frequency).prn(prnHours)
            .risks(ParacetamolRisk.TWO_GRAM_GROUP, ParacetamolRisk.CHRONIC_ALCOHOLISM));
        assertThat(letters(cards)).isEqualTo(expected);
        cards.forEach(card -> assertThat(card.summary()).contains("8 hours required in chronic alcoholism"));
    }

    @Test
    void severeHepaticInsufficiencyIsAContraindicationNotALowerDose() {
        Order any = new Order("500 mg", "3 fois par jour").risks(ParacetamolRisk.SEVERE_HEPATIC);
        assertThat(letters(evaluate(any))).isEqualTo("C");
        // A dose nobody can read is still contraindicated; the general ceiling stands beside it.
        assertThat(letters(evaluate(new Order("5 ml", "3 fois par jour").risks(ParacetamolRisk.SEVERE_HEPATIC)))).isEqualTo("C");
        assertThat(letters(evaluate(new Order("1 g", "4 fois par jour").risks(ParacetamolRisk.SEVERE_HEPATIC)))).isEqualTo("CN");
        assertThat(letters(evaluate(new Order("1 g", "5 fois par jour").risks(ParacetamolRisk.SEVERE_HEPATIC)))).isEqualTo("CD");
    }

    @Test
    void aDegreeNotRecordedSaysWhatSevereWouldMean() {
        List<CdsCard> cards = evaluate(new Order("501 mg", "4 fois par jour")
            .risks(ParacetamolRisk.TWO_GRAM_GROUP, ParacetamolRisk.HEPATIC_DEGREE_UNRECORDED));
        assertThat(cards.get(0).detail()).contains("not recorded").contains("contraindicated if it is severe");
    }

    @ParameterizedTest(name = "{0} {1} plus {2} {3}: [{4}] total {5}")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        // Each alone is fine; together they are over.
        "1 g | 3 fois par jour | 500 mg | 3 fois par jour | D | 4500",
        "500 mg | 1 fois par jour | 500 mg | 7 fois par jour | D | 4000.0",
        // Exactly at the ceiling across two orders: no ceiling alert.
        "1 g | 2 fois par jour | 1 g | 2 fois par jour | N | null",
        "500 mg | 3 fois par jour | 500 mg | 3 fois par jour | '' | null",
        // A count of tablets on the other order, through its strength.
        "1 g | 3 fois par jour | 3 cp | 1 fois par jour | D | 4500",
        // An unread frequency on the other order counts one dose at least.
        "1 g | 3 fois par jour | 1 g | selon la douleur | N | null",
        "1 g | 4 fois par jour | 1 g | selon la douleur | D | 5000",
    })
    void countsTheOtherActiveOrders(String dose, String frequency, String otherDose, String otherFrequency,
                                    String expected, String total) {
        if ("4000.0".equals(total)) {
            // 500 mg once and 500 mg seven times is 4,000 mg: at the ceiling, not over it.
            expected = "N";
            total = null;
        }
        List<CdsCard> cards = evaluate(new Order(dose, frequency).others(other(otherDose, otherFrequency)));
        assertThat(letters(cards)).isEqualTo(expected);
        if (total != null) {
            assertThat(cards.get(0).summary()).contains(total + " mg per day of paracetamol from all active orders");
            assertThat(cards.get(0).detail()).contains("counts 1 other active order");
        }
    }

    @Test
    void anAsNeededOtherOrderCountsFromItsInterval() {
        ParacetamolFacts.OtherOrder prnOther = new ParacetamolFacts.OtherOrder(Prescription.builder()
            .medicationName("Paracétamol").dosage("1 g").frequency("si besoin").asNeeded(true).prnMinIntervalHours(6)
            .build(), tablet500());
        List<CdsCard> cards = evaluate(new Order("1 g", null).others(prnOther));
        assertThat(letters(cards)).isEqualTo("D");
        assertThat(cards.get(0).summary()).contains("5000 mg per day");
    }

    @Test
    void anOtherOrderWhoseDoseIsUnknownIsToldNotGuessed() {
        ParacetamolFacts.OtherOrder unknown = new ParacetamolFacts.OtherOrder(Prescription.builder()
            .medicationName("Paracétamol").dosage("20 cp").frequency("3 fois par jour").build(), null);
        List<CdsCard> cards = evaluate(new Order("1 g", "5 fois par jour").others(unknown));
        assertThat(cards.get(0).summary()).contains("5000 mg per day");
        assertThat(cards.get(0).detail()).contains("1 other active order(s) could not be counted");
    }

    @Test
    void theTextNamesTheGroupsAndSaysWhenNoWeightIsOnFile() {
        String noWeight = evaluate(new Order("1 g", "5 fois par jour").weight(null)).get(0).detail();
        assertThat(noWeight).contains("No weight on file: a lower limit applies under 50 kg");
        assertThat(noWeight).contains("Gilbert syndrome").contains("chronic alcoholism").contains("chronic malnutrition")
            .contains("contraindicated in severe hepatocellular insufficiency");
        assertThat(evaluate(new Order("1 g", "5 fois par jour")).get(0).detail()).doesNotContain("No weight on file");
    }

    @Test
    void anOldLowWeightIsFlaggedAndStillUsed() {
        Order order = new Order("1 g", "3 fois par jour").weight(45d);
        order.weighedAt = LocalDateTime.now().minusDays(31);
        assertThat(evaluate(order).get(0).detail()).contains("recorded 31 days ago");
        order.weighedAt = LocalDateTime.now().minusDays(30);
        assertThat(evaluate(order).get(0).detail()).doesNotContain("days ago");
    }

    @Test
    void everyCardIsAnAlertNoneBlocks() {
        List<CdsCard> all = new ArrayList<>();
        all.addAll(evaluate(new Order("2 000 mg", "3 fois par jour")));
        all.addAll(evaluate(new Order("1 g", "si besoin").prn(1)));
        all.addAll(evaluate(new Order("1 g", "selon la douleur")));
        all.addAll(evaluate(new Order("1 g", "4 fois par jour").risks(ParacetamolRisk.SEVERE_HEPATIC,
            ParacetamolRisk.CHRONIC_ALCOHOLISM)));
        assertThat(all).hasSizeGreaterThanOrEqualTo(6);
        assertThat(all).allSatisfy(card -> {
            assertThat(card.indicator()).isNotEqualTo(CdsCard.Indicator.CRITICAL);
            assertThat(card.blocksOverride()).isFalse();
            assertThat(card.reviewRequired()).isNull();
            assertThat(card.detail()).contains("does not block").contains("EFFERALGANSTIX").contains("FDA");
        });
        assertThat(evaluate(new Order("1 g", "4 fois par jour")).get(0).indicator()).isEqualTo(CdsCard.Indicator.INFO);
        assertThat(evaluate(new Order("2 g", null)).get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
    }

    @ParameterizedTest(name = "age boundary, adult = {1}")
    @CsvSource({"18, true", "40, true", "17, false", "0, false"})
    void adultsOnlyAtTheRepositorysOwnAge(int years, boolean adult) {
        Order order = new Order("2 000 mg", "3 fois par jour");
        order.patient = agedYears(years);
        assertThat(!evaluate(order).isEmpty()).isEqualTo(adult);
        assertThat(PediatricDoseRule.isAdult(order.patient)).isEqualTo(adult);
    }

    @Test
    void theDayBeforeTheEighteenthBirthdayIsAChild() {
        Patient patient = Patient.builder().build();
        patient.setDateOfBirth(LocalDate.now().minusYears(18).plusDays(1));
        assertThat(PediatricDoseRule.isAdult(patient)).isFalse();
        patient.setDateOfBirth(LocalDate.now().minusYears(18));
        assertThat(PediatricDoseRule.isAdult(patient)).isTrue();
    }

    @Test
    void noBirthDateAndAnotherDrugAreSilent() {
        Order noBirthDate = new Order("2 000 mg", "3 fois par jour");
        noBirthDate.patient = Patient.builder().build();
        assertThat(evaluate(noBirthDate)).isEmpty();
        assertThat(rule.evaluate(new CdsRuleContext(agedYears(40), UUID.randomUUID(), "Amoxicilline", null, null, 5000d,
            null, 70d, List.of(), List.of()))).isEmpty();
    }

    @Test
    void theBundlesCarryEveryCardInFrenchAndSpanish() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(com.example.hms.config.MessageBundles.basenames());
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        rule.setCardText(new CdsCardText(source));
        try {
            for (Locale reader : List.of(Locale.FRENCH, Locale.forLanguageTag("es"))) {
                LocaleContextHolder.setLocale(reader);
                List<CdsCard> cards = new ArrayList<>();
                cards.addAll(evaluate(new Order("2 000 mg", "3 fois par jour").weight(null)));
                cards.addAll(evaluate(new Order("1 g", "4 fois par jour")));
                cards.addAll(evaluate(new Order("1 g", "selon la douleur")));
                cards.addAll(evaluate(new Order("1 g", "toutes les 2 heures").weight(45d).others(other("20 cp", null))));
                cards.addAll(evaluate(new Order("501 mg", "4 fois par jour").risks(ParacetamolRisk.values())));
                assertThat(cards).hasSizeGreaterThanOrEqualTo(8).allSatisfy(card -> {
                    assertThat(card.summary() + " " + card.detail())
                        .doesNotContain("per day").doesNotContain("between doses").doesNotContain("does not block")
                        .doesNotContain("''").doesNotContain("{");
                    assertThat(card.source().label()).doesNotContain("Check");
                });
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
