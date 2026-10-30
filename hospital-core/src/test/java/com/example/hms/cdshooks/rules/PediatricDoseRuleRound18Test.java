package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.PediatricWeightBand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
            .nameFr("Paracétamol").genericName("Paracetamol").strength("500").strengthUnit("mg")
            .pediatricMaxDoseMgPerKg(new BigDecimal("15"))
            .pediatricMaxDailyMgPerKg(new BigDecimal("60"))
            .pediatricMaxSingleDoseMg(new BigDecimal("1000"))
            .pediatricMaxDailyMg(new BigDecimal("4000"))
            .pediatricDoseSource("WHO Pocket Book of Hospital Care for Children 2013; BNFc")
            .pediatricDoseStatus(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL)
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
            .strengthUnit("mg").pediatricWeightBands(bands)
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
            frequency, CdsRuleEngine.parseDoseUnits(dosage), weighedAt);
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
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 g", "4 fois par jour",
            19.0, LocalDateTime.now().minusDays(2)));

        assertThat(critical(cards)).isEqualTo(2); // per dose mg/kg, per day mg/kg
        assertThat(anyHardStop(cards)).as("4 g/day is the cap itself, not above it").isFalse();
        assertThat(cards).allSatisfy(c -> assertThat(c.detail()).contains("default, pending local approval")
            .contains("WHO Pocket Book"));
    }

    @Test
    void aboveTheAbsoluteDailyMaximumIsAHardStop() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "1 g", "q4h",
            19.0, LocalDateTime.now()));

        assertThat(anyHardStop(cards)).isTrue();
        assertThat(cards.stream().filter(CdsCard::blocksOverride).findFirst().orElseThrow().summary())
            .contains("6000.00 mg").contains("4000");
    }

    /** Review of round 18: an unreadable frequency still counts the dose itself against the daily cap. */
    @Test
    void oneDoseAboveTheDailyCapIsAHardStopWhateverTheFrequency() {
        List<CdsCard> cards = rule.evaluate(ctx(childAged(6, 0, 0), paracetamol(), "5 g", "si douleur",
            19.0, LocalDateTime.now()));

        assertThat(anyHardStop(cards)).isTrue();
    }

    /** Review round 2: a count that does not divide the day, and an interval over a day, still count. */
    @Test
    void fiveTimesADayAndEveryTwoDaysAreCountedAgainstTheDailyCap() {
        MedicationCatalogItem capped = MedicationCatalogItem.builder()
            .nameFr("Aciclovir").genericName("Aciclovir").strength("200").strengthUnit("mg")
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
            .nameFr("Morphine").genericName("Morphine").strength("10").strengthUnit("mg")
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
}
