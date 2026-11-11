package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.PediatricWeightBand;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 18 (QA D935): the paediatric dose check as real hospitals run it.
 * The limits below are the round-18 dev defaults (reference-values file of
 * 2026-10-30, section 4); the test pins the behaviour, not the clinical
 * values, which the hospital pharmacy approves.
 */
class PediatricDoseRuleRound18Test {

    private final PediatricDoseRule rule = new PediatricDoseRule();

    private static Patient childAged(int years, int months, int days) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(years).minusMonths(months).minusDays(days));
        return p;
    }

    /** Paracétamol 500 mg: 15 mg/kg (1 g) per dose, 60 mg/kg (4 g) per day. */
    private static MedicationCatalogItem paracetamol() {
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("Paracétamol").genericName("Paracetamol").strength("500").strengthUnit("mg").route("PO")
            .pediatricMaxDoseMgPerKg(new BigDecimal("15"))
            .pediatricMaxDailyMgPerKg(new BigDecimal("60"))
            .pediatricMaxSingleDoseMg(new BigDecimal("1000"))
            .pediatricMaxDailyMg(new BigDecimal("4000"))
            .pediatricDoseSource("WHO Pocket Book of Hospital Care for Children 2013; BNFc")
            .pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    /** Artéméther-luméfantrine 20/120: weight bands, twice a day. */
    private static MedicationCatalogItem artemetherLumefantrine() {
        List<PediatricWeightBand> bands = new ArrayList<>(List.of(
            band("5", "15", "1"), band("15", "25", "2"), band("25", "35", "3"), band("35", null, "4")));
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("Artéméther-luméfantrine").genericName("Artemether/Lumefantrine").strength("20/120")
            .strengthUnit("mg").route("PO").pediatricWeightBands(bands)
            .pediatricDoseSource("WHO Guidelines for malaria 2023")
            .pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static PediatricWeightBand band(String min, String max, String tablets) {
        return PediatricWeightBand.builder()
            .minWeightKg(new BigDecimal(min))
            .maxWeightKg(max == null ? null : new BigDecimal(max))
            .dosePerAdministration(new BigDecimal(tablets))
            .doseUnit("cp")
            .maxDosesPerDay(2)
            .build();
    }

    private static CdsRuleContext ctx(Patient patient, MedicationCatalogItem item, String dosage, String frequency,
                                      Double weightKg, LocalDateTime weighedAt) {
        return new CdsRuleContext(patient, UUID.randomUUID(), item == null ? "x" : item.getNameFr(), null, null,
            CdsRuleEngine.parseDoseMg(dosage, weightKg, item), item, weightKg, List.of(), List.of(),
            frequency, CdsRuleEngine.parseDoseUnits(dosage), weighedAt).withRoute("PO", null);
    }

    private static long critical(List<CdsCard> cards) {
        return cards.stream().filter(c -> c.indicator() == CdsCard.Indicator.CRITICAL).count();
    }

    private static boolean anyHardStop(List<CdsCard> cards) {
        return cards.stream().anyMatch(CdsCard::blocksOverride);
    }

    /** The QA D935 order: 1 g four times a day for a 6-year-old of 19 kg (about 210 mg/kg/day). */
    @Test
    void d935_oneGramFourTimesADayForA19KgChildIsASoftStop() {
        MedicationCatalogItem pending = paracetamol();
        pending.setPediatricDoseStatus(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL);
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), pending, "1 g", "4 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)));

        assertThat(critical(cards)).isEqualTo(2); // per dose mg/kg, per day mg/kg
        assertThat(anyHardStop(cards)).as("4 g/day is the cap itself, not above it").isFalse();
        assertThat(cards).allSatisfy(c -> assertThat(c.detail()).contains("default, pending local approval")
            .contains("WHO Pocket Book"));
    }

    /**
     * Round 33: the limits judge the high end of a range. Paracetamol 15 mg/kg for 19 kg is
     * 285 mg, and 1000 mg a dose at most: each of these passed, read at its first number.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "200-400 mg", "200 \u00e0 400 mg", "de 200 a 400 mg", "200 to 400 mg", "entre 200 y 400 mg", "200 mg - 400 mg",
        "0,2-0,4 g", "0,2 \u00e0 0,4 g", "0.2 to 0.4 g", "10-20 mg/kg", "10 \u00e0 20 mg/kg",
    })
    void aRangeOverTheLimitAtItsHighEndIsASoftStop(String dosage) {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), dosage, "1 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)));

        assertThat(critical(cards)).as(dosage).isEqualTo(1);
        assertThat(cards.get(0).summary()).as(dosage).contains("mg/kg");
    }

    @Test
    void aRangeWithinTheLimitAtItsHighEndPasses() {
        for (String dosage : List.of("100-200 mg", "100 \u00e0 200 mg", "de 100 a 200 mg", "100 to 200 mg")) {
            assertThat(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), dosage, "1 fois par jour",
                19.0, LocalDateTime.now().minusDays(2)))).as(dosage).isEmpty();
        }
    }

    @Test
    void aRangeOfTabletsOverItsWeightBandAtTheHighEndIsASoftStop() {
        // 19 kg is the band of 2 tablets.
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), artemetherLumefantrine(), "2-3 cp", "2 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)));

        assertThat(critical(cards)).isEqualTo(2);
    }

    @Test
    void aSingleDoseKeepsItsVerdict() {
        // At the limit, and just over it: the outcome of a valid single dose does not move.
        assertThat(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "285 mg", "1 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)))).isEmpty();
        assertThat(critical(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "286 mg", "1 fois par jour",
            19.0, LocalDateTime.now().minusDays(2))))).isEqualTo(1);
        assertThat(rule.evaluate(ctx(childAged(6, 0, 0), artemetherLumefantrine(), "2 cp", "2 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)))).isEmpty();
    }

    private static CdsRuleContext prnCtx(String dosage, String frequency, Integer intervalHours) {
        double weightKg = 19.0;
        MedicationCatalogItem item = paracetamol();
        return new CdsRuleContext(childAged(6, 0, 0), UUID.randomUUID(), item.getNameFr(), null, null,
            CdsRuleEngine.parseDoseMg(dosage, weightKg, item), item, weightKg, List.of(), List.of(),
            frequency, CdsRuleEngine.parseDoseUnits(dosage), LocalDateTime.now(),
            com.example.hms.service.impl.PrnMinInterval.highestDosesPerDay(intervalHours != null, intervalHours)).withRoute("PO", null);
    }

    /**
     * QA D1397 (round 33): paracetamol 250 mg as needed with a minimum interval
     * of 1 h is at most 6 g a day, above the absolute 4000 mg: the same hard
     * stop as "q1h". It got a warning that the total could not be read.
     */
    @Test
    void d1397_aTickedIntervalIsJudgedByTheDailyLimits() {
        List<CdsCard> ticked = rule.evaluate(prnCtx("250 mg", "si fi\u00e8vre", 1));
        assertThat(anyHardStop(ticked)).as("250 mg x 24 is above 4000 mg").isTrue();
        assertThat(critical(ticked)).as("the hard stop and the mg/kg/day stop").isGreaterThanOrEqualTo(2);
        assertThat(ticked).noneMatch(c -> c.summary().contains("could not be read"));

        List<CdsCard> fixed = rule.evaluate(prnCtx("250 mg", "q1h", null));
        assertThat(anyHardStop(fixed)).as("the same stop as the fixed schedule").isTrue();

        List<CdsCard> safe = rule.evaluate(prnCtx("250 mg", "si fi\u00e8vre", 6));
        assertThat(anyHardStop(safe)).as("4 doses of 250 mg is 1 g").isFalse();
        assertThat(critical(safe)).isZero();

        // The text can only ask for more: a ticked "q1h" with a 6 h interval stays stopped, as before.
        assertThat(anyHardStop(rule.evaluate(prnCtx("250 mg", "q1h", 6)))).isTrue();

        // No interval (refused later by the prescription rule): the warning stands, as before.
        assertThat(rule.evaluate(prnCtx("250 mg", "si fi\u00e8vre", null)))
            .anyMatch(c -> c.summary().contains("could not be read"));
    }

    @Test
    void aboveTheAbsoluteDailyMaximumIsAHardStop() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 g", "q4h",
            19.0, LocalDateTime.now()));

        assertThat(anyHardStop(cards)).isTrue();
        assertThat(cards.stream().filter(CdsCard::blocksOverride).findFirst().orElseThrow().summary())
            // QA D1133 (changed assertion): no padded zeros, "6000 mg" (was "6000.00 mg").
            .contains("6000 mg").doesNotContain("6000.00").contains("4000");
    }

    /** Review of round 18: an unreadable frequency still counts the dose itself against the daily cap. */
    @Test
    void oneDoseAboveTheDailyCapIsAHardStopWhateverTheFrequency() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "5 g", "si douleur",
            19.0, LocalDateTime.now()));

        assertThat(anyHardStop(cards)).isTrue();
    }

    private static final String UNREAD_COUNT = "3 fois chaque jour";

    private static boolean saysTotalUnknown(List<CdsCard> cards) {
        return cards.stream().anyMatch(card -> card.summary().contains("the daily total could not be read from the frequency"));
    }

    /**
     * QA D1455, its own case: a 19 kg child, 750 mg. "3 fois par jour" is judged at 118,42 mg/kg/day; "3 fois
     * chaque jour" saved with no dose card, read as one dose a day. It is now not read, and a card says the total
     * could not be calculated, on every path, and the limit per dose still stands.
     */
    @Test
    void d1455_anUnreadCountIsNeverOneDoseADayForAChild() {
        List<CdsCard> read = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "750 mg", "3 fois par jour",
            19.0, LocalDateTime.now()));
        assertThat(read.stream().map(CdsCard::summary)).anyMatch(summary -> summary.contains("118.42 mg/kg per day"));
        assertThat(saysTotalUnknown(read)).as("a read count has a known total").isFalse();

        List<CdsCard> unread = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "750 mg", UNREAD_COUNT,
            19.0, LocalDateTime.now()));
        assertThat(saysTotalUnknown(unread)).isTrue();
        assertThat(unread.stream().map(CdsCard::summary))
            .as("never a daily figure from one dose").noneMatch(summary -> summary.contains("mg/kg per day"))
            .as("the limit per dose stands").anyMatch(summary -> summary.contains("exceeds ceiling"));
    }

    /** QA D1455: the dose alone over the cap keeps the hard stop, which says "at least" and that the total is unknown. */
    @Test
    void d1455_theHardStopStandsOnTheDoseAlone() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "5 g", UNREAD_COUNT,
            19.0, LocalDateTime.now()));
        assertThat(anyHardStop(cards)).isTrue();
        assertThat(cards.get(0).summary()).contains("at least 5000 mg per day").contains("the daily total is unknown");
        assertThat(saysTotalUnknown(cards)).as("the hard stop says it itself").isFalse();

        assertThat(anyHardStop(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1500 mg", "3 fois par jour pendant 5 jours",
            19.0, LocalDateTime.now())))).as("the count the sentence states still meets the cap: 4500 mg").isTrue();
    }

    /** QA D1455: the paths that never reach the mg/kg check say it too. */
    @Test
    void d1455_everyPathSaysTheTotalIsUnknown() {
        assertThat(saysTotalUnknown(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "250 mg", UNREAD_COUNT,
            null, null)))).as("no weight on file").isTrue();
        assertThat(saysTotalUnknown(rule.evaluate(ctx(childAged(0, 1, 0), paracetamol(), "50 mg", UNREAD_COUNT,
            4.0, LocalDateTime.now())))).as("under the manual-review age").isTrue();
        assertThat(saysTotalUnknown(rule.evaluate(ctx(childAged(6, 0, 0), artemetherLumefantrine(), "2 cp", "2 fois chaque jour",
            19.0, LocalDateTime.now())))).as("weight bands").isTrue();
        assertThat(saysTotalUnknown(rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "250 mg", "3 fois par jour",
            19.0, LocalDateTime.now())))).as("a schedule").isFalse();
        assertThat(saysTotalUnknown(rule.evaluate(ctx(childAged(6, 0, 0), MedicationCatalogItem.builder().nameFr("Sans limite").build(),
            "250 mg", UNREAD_COUNT, 19.0, LocalDateTime.now())))).as("no daily limit to judge").isFalse();
    }

    /** Review round 2: a count that does not divide the day, and an interval over a day, still count. */
    @Test
    void fiveTimesADayAndEveryTwoDaysAreCountedAgainstTheDailyCap() {
        MedicationCatalogItem capped = MedicationCatalogItem.builder()
            .nameFr("Aciclovir").genericName("Aciclovir").strength("200").strengthUnit("mg").route("PO").pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .pediatricMaxDailyMg(new BigDecimal("1000"))
            .build();

        assertThat(anyHardStop(rule.evaluate(ctx(childAged(6, 0, 0), capped, "300 mg", "5 fois par jour",
            19.0, LocalDateTime.now())))).as("5 x 300 = 1500 mg > 1000").isTrue();
        assertThat(anyHardStop(rule.evaluate(ctx(childAged(6, 0, 0), capped, "1200 mg", "q48h",
            19.0, LocalDateTime.now())))).as("a full dose on the dosing day").isTrue();
        assertThat(anyHardStop(rule.evaluate(ctx(childAged(6, 0, 0), capped, "150 mg", "5 fois par jour",
            19.0, LocalDateTime.now())))).isFalse();
        assertThat(com.example.hms.service.impl.MarDoseSchedule.dosesPerDay("5 fois par jour")).isEqualTo(5.0);
        assertThat(com.example.hms.service.impl.MarDoseSchedule.dosesPerDay("q48h")).isEqualTo(0.5);
        assertThat(com.example.hms.service.impl.MarDoseSchedule.dosesPerDay("q6h")).isEqualTo(4.0);
        assertThat(com.example.hms.service.impl.MarDoseSchedule.dosesPerDay("si besoin")).isNull();
    }

    @Test
    void correctDosesRaiseNothing() {
        Patient child = childAged(6, 0, 0);
        assertThat(rule.evaluate(ctx(child, paracetamol(), "250 mg", "toutes les 6 h", 19.0, LocalDateTime.now())))
            .isEmpty();
        assertThat(rule.evaluate(ctx(child, paracetamol(), "15 mg/kg", "q6h", 19.0, LocalDateTime.now())))
            .isEmpty();
    }

    @Test
    void tabletsAreReadThroughTheStrength() {
        // 1 cp of 500 mg for a 19 kg child: 26 mg/kg, above 15 mg/kg per dose
        // (it was read as 1 mg before round 18); 52.6 mg/kg/day is within the day.
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 cp", "BID",
            19.0, LocalDateTime.now()));

        assertThat(critical(cards)).isEqualTo(1);
    }

    @Test
    void underThreeMonthsThereIsNoAutomaticVerdictOnlyAManualReview() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(0, 2, 0), paracetamol(), "250 mg", "q6h",
            4.5, LocalDateTime.now()));

        assertThat(critical(cards)).isZero();
        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(c.summary()).contains("under 3 months");
        });
    }

    @Test
    void theAbsoluteDailyMaximumStillStopsAnInfant() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(0, 2, 0), paracetamol(), "1 g", "q4h",
            4.5, LocalDateTime.now()));

        assertThat(anyHardStop(cards)).isTrue();
    }

    @Test
    void morphineUnderOneYearNeedsAManualReview() {
        MedicationCatalogItem morphine = MedicationCatalogItem.builder()
            .nameFr("Morphine").genericName("Morphine").strength("10").strengthUnit("mg").route("PO").pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .pediatricMaxDoseMgPerKg(new BigDecimal("0.1"))
            .pediatricManualReviewUnderMonths(12)
            .pediatricDoseSource("WHO persisting pain in children 2012")
            .build();
        List<CdsCard> cards = rule.evaluate(ctx(childAged(0, 8, 0), morphine, "5 mg", "q4h",
            8.0, LocalDateTime.now()));

        assertThat(critical(cards)).isZero();
        assertThat(cards).singleElement().satisfies(c -> assertThat(c.summary()).contains("under 12 months"));
    }

    @Test
    void anOldWeightIsFlaggedAndTheChecksStillRun() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 g", "q6h",
            19.0, LocalDateTime.now().minusDays(45)));

        assertThat(cards).anySatisfy(c -> {
            assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.WARNING);
            assertThat(c.summary()).contains("45 days ago");
        });
        assertThat(critical(cards)).isEqualTo(2);
    }

    @Test
    void aMissingWeightMeansNoMgPerKgVerdict() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 g", "q6h", null, null));

        assertThat(critical(cards)).isZero();
        assertThat(cards).singleElement().satisfies(c -> assertThat(c.detail()).contains("weight not on file"));
    }

    @Test
    void weightBandsReplaceMgPerKg() {
        MedicationCatalogItem al = artemetherLumefantrine();
        Patient child = childAged(5, 0, 0);

        assertThat(rule.evaluate(ctx(child, al, "2 cp", "2 fois par jour", 18.0, LocalDateTime.now()))).isEmpty();

        List<CdsCard> tooMany = rule.evaluate(ctx(child, al, "3 cp", "2 fois par jour", 18.0, LocalDateTime.now()));
        assertThat(critical(tooMany)).isEqualTo(2); // per dose and per day of the 15-25 kg band
        assertThat(tooMany).allSatisfy(c -> assertThat(c.detail()).contains("approved locally"));

        List<CdsCard> tooOften = rule.evaluate(ctx(child, al, "2 cp", "TID", 18.0, LocalDateTime.now()));
        assertThat(critical(tooOften)).isEqualTo(1); // 6 a day against 4
    }

    @Test
    void aChildOutsideEveryBandNeedsAManualReview() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(0, 6, 0), artemetherLumefantrine(), "1 cp", "BID",
            4.0, LocalDateTime.now()));

        assertThat(critical(cards)).isZero();
        assertThat(cards).singleElement().satisfies(c -> assertThat(c.summary()).contains("no weight band"));
    }

    @Test
    void anUnreadableFrequencyIsFlaggedWhenADailyLimitExists() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "250 mg", "si douleur",
            19.0, LocalDateTime.now()));

        assertThat(critical(cards)).isZero();
        assertThat(cards).singleElement().satisfies(c -> assertThat(c.summary()).contains("si douleur"));
    }

    @Test
    void doseParsingNoLongerReadsOtherUnitsAsMilligrams() {
        assertThat(CdsRuleEngine.parseDoseMg("5 ml", 19.0, paracetamol())).isNull();
        assertThat(CdsRuleEngine.parseDoseMg("2 cp", 19.0, artemetherLumefantrine())).isNull();
        assertThat(CdsRuleEngine.parseDoseMg("2 cp", 19.0, paracetamol())).isEqualTo(1000.0);
        assertThat(CdsRuleEngine.parseDoseMg("500 mcg", 19.0, null)).isEqualTo(0.5);
        assertThat(CdsRuleEngine.parseDoseMg("0,5 g", 19.0, null)).isEqualTo(500.0);
        assertThat(CdsRuleEngine.parseDoseMg("500 mg", 19.0, null)).isEqualTo(500.0);
        assertThat(CdsRuleEngine.parseDoseUnits("2 comprimés")).isEqualTo(2.0);
        assertThat(CdsRuleEngine.parseDoseUnits("2")).isEqualTo(2.0);
        assertThat(CdsRuleEngine.parseDoseUnits("500 mg")).isNull();
    }

    /**
     * Round-18 merge (QA D1078 / D1049 class): the paediatric card's values carry
     * the reader's decimal separator - the mg/kg computed for the child, the
     * configured ceiling and the weight - through CdsCardText.decimal, never the
     * Locale.ROOT point the rule wrote before.
     */
    @Test
    void theCardsDecimalsAreWrittenForTheReader() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(com.example.hms.config.MessageBundles.basenames());
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        PediatricDoseRule localized = new PediatricDoseRule();
        localized.setCardText(new com.example.hms.cdshooks.CdsCardText(source));
        MedicationCatalogItem morphine = MedicationCatalogItem.builder()
            .nameFr("Morphine").genericName("Morphine").strength("10").strengthUnit("mg").route("PO").pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .pediatricMaxDoseMgPerKg(new BigDecimal("0.1"))
            .pediatricDoseSource("WHO persisting pain in children 2012")
            .build();
        // 5 mg for a 2-year-old of 10.5 kg: 0.48 mg/kg, above the 0.1 mg/kg ceiling.
        CdsRuleContext order = ctx(childAged(2, 0, 0), morphine, "5 mg", "q4h", 10.5, LocalDateTime.now());
        try {
            for (Locale reader : List.of(Locale.FRENCH, Locale.forLanguageTag("es"))) {
                LocaleContextHolder.setLocale(reader);
                CdsCard ceiling = localized.evaluate(order).stream()
                    .filter(c -> c.summary().contains("mg/kg")).findFirst().orElseThrow();
                assertThat(ceiling.summary()).as(reader.toString())
                    .contains("0,48 mg/kg").contains("0,1 mg/kg").doesNotContain("0.48").doesNotContain("0.1 ");
                // QA D1133 (changed assertion): "10,5" with no padded zero (was "10,50"); still no point.
                assertThat(ceiling.detail()).as(reader.toString()).contains("10,5 ").doesNotContain("10.5")
                    .doesNotContain("10,50");
            }
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            CdsCard english = localized.evaluate(order).stream()
                .filter(c -> c.summary().contains("mg/kg")).findFirst().orElseThrow();
            assertThat(english.summary()).contains("0.48 mg/kg").contains("0.1 mg/kg");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /** QA D1462: a free-text brand reads the limits the reader found for the drug; a count stays unread. */
    @Test
    void aFreeTextBrandIsStoppedLikeAPickedItem() {
        MedicationCatalogItem limits = MedicationCatalogItem.builder().genericName("Paracétamol").route("PO").pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .pediatricMaxDailyMg(java.math.BigDecimal.valueOf(1000)).build();
        ParacetamolFacts facts = new ParacetamolFacts(true, List.of(), java.util.Set.of(), List.of(), List.of(), false, false, false,
            limits, List.of(), false);
        Patient child = Patient.builder().dateOfBirth(java.time.LocalDate.now().minusYears(6)).build();
        CdsRuleContext context = new CdsRuleContext(child, java.util.UUID.randomUUID(), "Efferalgan 1 g", null, null, 1000d,
            null, 19d, List.of(), List.of(), "4 fois par jour", null, java.time.LocalDateTime.now(), null, facts).withRoute("PO", null);
        List<com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard> cards = new PediatricDoseRule().evaluate(context);
        org.assertj.core.api.Assertions.assertThat(cards)
            .anySatisfy(card -> org.assertj.core.api.Assertions.assertThat(card.hardStop()).isTrue());

        CdsRuleContext noFacts = new CdsRuleContext(child, java.util.UUID.randomUUID(), "Efferalgan 1 g", null, null, 1000d,
            null, 19d, List.of(), List.of(), "4 fois par jour", null, java.time.LocalDateTime.now(), null, ParacetamolFacts.NONE).withRoute("PO", null);
        org.assertj.core.api.Assertions.assertThat(new PediatricDoseRule().evaluate(noFacts))
            .noneSatisfy(card -> org.assertj.core.api.Assertions.assertThat(card.hardStop()).isTrue());
    }
}
