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
 * QA D2552 (round 65): an adult artemether-lumefantrine dose (4 tablets twice a day) signed for a
 * 2-year-old of 12 kg with an advisory only, because the hospital's catalogue held no weight band for
 * the drug. The WHO bands (Guidelines for the treatment of malaria, 3rd ed. 2015, section 4) now
 * stand in, provisional and pending local approval.
 */
class PediatricDoseWhoArtemetherLumefantrineTest {

    private final PediatricDoseRule rule = new PediatricDoseRule();

    private static Patient childAged(int years) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(years).minusDays(10));
        return p;
    }

    /** The dev catalogue row of the QA run: no paediatric limit of its own. */
    private static MedicationCatalogItem adultAlWithNoLimit(String strength) {
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("Artéméther-luméfantrine adulte " + strength + " mg").genericName("Artemether/Lumefantrine")
            .strength(strength).strengthUnit("mg").route("PO").form("comprimé")
            .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static CdsRuleContext ctx(String name, MedicationCatalogItem item, String dosage, String frequency,
                                      Double weightKg) {
        return new CdsRuleContext(childAged(2), UUID.randomUUID(), name, null, null,
            CdsRuleEngine.parseDoseMg(dosage, weightKg, item), item, weightKg, List.of(), List.of(),
            frequency, CdsRuleEngine.parseDoseUnits(dosage), LocalDateTime.now()).withRoute("PO", null);
    }

    private static long critical(List<CdsCard> cards) {
        return cards.stream().filter(c -> c.indicator() == CdsCard.Indicator.CRITICAL).count();
    }

    @Test
    void d2552_theAdultDoseForA12KgChildIsStoppedAndHeld() {
        MedicationCatalogItem al = adultAlWithNoLimit("20/120");
        List<CdsCard> cards = rule.evaluate(ctx(al.getNameFr(), al, "4 cp", "2 fois par jour", 12.0));

        // 4 tablets per dose against 1, and 8 a day against 2: both above the 5-15 kg band.
        assertThat(critical(cards)).isEqualTo(2);
        assertThat(cards).anySatisfy(c -> assertThat(c.summary()).contains("12 kg").contains("4"));
        // Pending local approval: the review is asked for too, never a plain advisory.
        assertThat(cards).anySatisfy(c -> {
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.summary()).contains("WHO weight bands").contains("provisional");
        });
        assertThat(cards).noneSatisfy(c -> assertThat(c.summary()).contains("verify weight-based dosing"));
        assertThat(cards).allSatisfy(c -> assertThat(c.detail()).contains("WHO Guidelines for the treatment of malaria"));
    }

    @Test
    void aDoseInTheBandShowsTheBandAndIsStillHeldForReview() {
        MedicationCatalogItem al = adultAlWithNoLimit("20/120");
        List<CdsCard> cards = rule.evaluate(ctx(al.getNameFr(), al, "1 cp", "BID", 12.0));

        assertThat(critical(cards)).isZero();
        assertThat(cards).anySatisfy(c -> {
            assertThat(c.indicator()).isEqualTo(CdsCard.Indicator.INFO);
            assertThat(c.summary()).contains("WHO weight band for 12 kg").contains("1 tablet(s) of 20/120 mg");
        });
        assertThat(cards).anySatisfy(c -> assertThat(c.requiresReview()).isTrue());
    }

    @Test
    void aFreeTextBrandWithNoCatalogueItemIsJudgedByTheBands() {
        List<CdsCard> cards = rule.evaluate(ctx("Coartem", null, "3 cp", "BID", 18.0));

        assertThat(critical(cards)).isEqualTo(2); // 3 tablets against the 2 of the 15-25 kg band
    }

    @Test
    void anotherStrengthIsNotCountedInTwentyOneTwentyTablets() {
        MedicationCatalogItem al = adultAlWithNoLimit("80/480");
        List<CdsCard> cards = rule.evaluate(ctx(al.getNameFr(), al, "1 cp", "BID", 12.0));

        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.summary()).contains("20/120").contains("80/480");
        });
    }

    @Test
    void theHospitalsOwnApprovedBandsReplaceTheWhoDefault() {
        List<PediatricWeightBand> own = new ArrayList<>(List.of(PediatricWeightBand.builder()
            .minWeightKg(new BigDecimal("5")).maxWeightKg(new BigDecimal("15"))
            .dosePerAdministration(BigDecimal.ONE).maxDosesPerDay(2).build()));
        MedicationCatalogItem al = adultAlWithNoLimit("20/120");
        al.setPediatricWeightBands(own);
        al.setPediatricDoseSource("Formulaire local");
        al.setPediatricDoseStatus(PediatricDoseLimitStatus.APPROVED);

        assertThat(rule.evaluate(ctx(al.getNameFr(), al, "1 cp", "BID", 12.0))).isEmpty();
    }

    @Test
    void anotherDrugWithNoLimitKeepsTheAdvisory() {
        MedicationCatalogItem amox = MedicationCatalogItem.builder().nameFr("Amoxicilline 250 mg")
            .genericName("Amoxicillin").strength("250").strengthUnit("mg").route("PO").build();
        List<CdsCard> cards = rule.evaluate(ctx(amox.getNameFr(), amox, "250 mg", "TID", 12.0));

        assertThat(cards).singleElement().satisfies(c -> assertThat(c.summary()).contains("verify weight-based dosing"));
    }

    /**
     * Review of #1276: every way of writing the 80/480 mg tablet (or a strength not stated on a generic
     * name) is held as "cannot assess", never judged in tablets of 20/120 mg, where one tablet of
     * 80/480 mg read as in-band for 12 kg is four times the band's dose.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "Artéméther 80 mg + Luméfantrine 480 mg|80/480",
        "80 mg + 480 mg artemether-lumefantrine|80/480",
        "Artéméther 80 mg / Luméfantrine 480 mg|80/480",
        "Artemether-lumefantrine 80-480|80/480",
        "Coartem 80/480|80/480",
        "Coartem forte|forte",
        "Artéméther-luméfantrine DS|ds",
        "Artéméther-luméfantrine 80 mg|80",
        "Artéméther-luméfantrine|not stated",
    })
    void aStrengthNotClearlyTwentyOneTwentyIsHeldNotBanded(String written, String shown) {
        List<CdsCard> cards = rule.evaluate(ctx(written, null, "1 cp", "BID", 12.0));

        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.requiresReview()).isTrue();
            assertThat(c.indicator()).isNotEqualTo(CdsCard.Indicator.INFO);
            assertThat(c.summary()).contains("could not be matched").contains("(" + shown + ")");
        });
    }

    @Test
    void aWrittenStrengthOtherThanTheCataloguesIsHeldToo() {
        MedicationCatalogItem al = adultAlWithNoLimit("20/120");
        List<CdsCard> cards = rule.evaluate(ctx("Artéméther 80 mg + Luméfantrine 480 mg", al, "1 cp", "BID", 12.0));

        assertThat(cards).singleElement().satisfies(c -> assertThat(c.summary()).contains("(80/480)"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "Artéméther 20 mg + Luméfantrine 120 mg", "Artemether-lumefantrine 20-120", "Luméfantrine 120 mg / Artéméther 20 mg",
        "Coartem", "Coartem dispersible"})
    void aProductClearlyOfTwentyOneTwentyIsJudgedByTheBands(String written) {
        List<CdsCard> cards = rule.evaluate(ctx(written, null, "4 cp", "BID", 12.0));

        assertThat(critical(cards)).isEqualTo(2);
    }

    @Test
    void aGenericCatalogueItemOfTwentyOneTwentyWithANameWithNoStrengthIsBanded() {
        MedicationCatalogItem al = adultAlWithNoLimit("20/120");
        al.setNameFr("Artéméther-luméfantrine");

        assertThat(critical(rule.evaluate(ctx("Artéméther-luméfantrine", al, "4 cp", "BID", 12.0)))).isEqualTo(2);
    }

    @Test
    void aLongTextIsReadInBoundedTime() {
        String longText = "Artemether-lumefantrine " + "1".repeat(5000) + " mg " + "a/".repeat(5000);
        long start = System.nanoTime();
        rule.evaluate(ctx(longText, null, "1 cp", "BID", 12.0));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    /** The WHO table, pinned: 5-<15 kg 1, 15-<25 kg 2, 25-<35 kg 3, >=35 kg 4 tablets of 20/120 mg, twice a day. */
    @Test
    void theWhoBandsAreTheGuidelinesTable() {
        List<PediatricWeightBand> bands = ArtemetherLumefantrineWhoBands.bands();
        assertThat(bands).extracting(b -> b.getMinWeightKg().intValue()).containsExactly(5, 15, 25, 35);
        assertThat(bands).extracting(b -> b.getDosePerAdministration().intValue()).containsExactly(1, 2, 3, 4);
        assertThat(bands).extracting(PediatricWeightBand::getMaxDosesPerDay).containsOnly(2);
        assertThat(bands.get(3).getMaxWeightKg()).isNull();
        assertThat(bands.get(0).contains(4.9)).isFalse();
        assertThat(bands.get(0).contains(14.99)).isTrue();
    }
}
