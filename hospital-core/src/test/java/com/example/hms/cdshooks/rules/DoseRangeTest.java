package com.example.hms.cdshooks.rules;

import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Round 33: a dose written as a range is judged at its high end, in every language the desk types. */
@DisplayName("a dose range reads at its high end, and a single dose reads as it always did")
class DoseRangeTest {

    private static final double WEIGHT_KG = 20d;

    /** QA D1420: a mixed number exactly, thousands with a separator; every single dose as it was. */
    @ParameterizedTest(name = "\"{0}\" -> {1} mg")
    @CsvSource(delimiter = '|', value = {
        "2 1/2 cp|1250", "1 1/2 cp|750", "1 cp 1/2|750", "2 cp 1/2|1250", "1 cp et 1/2|750", "1 cp et demi|750",
        "1 comprimé et demi|750", "1 tablet and a half|750", "1 comprimido y medio|750", "2½ cp|1250", "1 ½ cp|750",
        "1 cp ½|750", "1 3/4 cp|875", "1,000 mg|1000", "1.000 mg|1000", "2,500 mg|2500", "1 000 mg|1000",
        "1,000-2,000 mg|2000", "1.000 mg PO|1000", "1,500 mg|1500", "1,250 mg|1250",
        // The high end of a range is read as written too.
        "1-2 1/2 cp|1250", "1 à 1 1/2 cp|750", "1/2 - 1 1/2 cp|750", "1½-2½ cp|1250",
        // A real dose, half an hour, and grams are not thousands or parts of a tablet.
        "3,125 mg|3.125", "3.125 mg|3.125", "1,875 mg|1.875",
                "1 cp 1/2 h avant le repas|500",
        // The second review: every way of joining a part, and the part after a range's high end.
        "1-1/2 tablets|750", "1 et 1/2 cp|750", "1 y 1/2 comprimidos|750", "1 and 1/2 tablets|750", "2-1/2 tabs|1250",
        "1 + 1/2 cp|750", "1 et ½ cp|750", "1-½ cp|750", "1 cp + 1/2|750", "1 tablet and 1/2|750", "1 tablet + 1/2|750",
        "1 tablet and one half|750", "1 et demi cp|750", "1 and a half tablets|750", "1 y medio comprimido|750",
        "1 cp et quart|625", "1 cp et trois quarts|875", "1 à 2 cp et demi|1250", "1 à 2 cp ½|1250",
        "1 cp à 1 cp 1/2|750", "1 cp à 1 cp et demi|750",

        // No valid single dose changes.
        "1/2 cp|250", "3/4 cp|375", "1 cp|500", "2 cp|1000", "0.5 cp|250", "0,5 cp|250", "500 mg|500", "0,125 mg|0.125",
        "0.125 mg|0.125", "1,5 mg|1.5", "1.25 mg|1.25", "1,25 mg|1.25", "12,500 mg|12.5", "1,250 g|1250", "1.5 g|1500",
        "1,000 g|1000", "1,000,000 mg|1", "500/125 mg|500"})
    void d1420_aMixedNumberAndAThousandReadAsWritten(String dose, double mg) {
        assertThat(CdsRuleEngine.parseDoseMg(dose, WEIGHT_KG, fiveHundredMg()))
            .as(dose).isCloseTo(mg, offset(0.0001d));
    }

    /** QA D1420: grams in any spelling are never closed up as thousands. */
    @ParameterizedTest
    @ValueSource(strings = {"1,500 gr", "1,250 grammes", "1,250 g", "1.500 gramos"})
    void d1420_gramsAreNeverThousands(String dose) {
        assertThat(DoseRange.highEnd(dose)).isSameAs(dose);
    }

    /** QA D1420: for a drug dosed by weight band, the count reads exactly too. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"2 1/2 cp|2.5", "1 cp 1/2|1.5", "1 cp et demi|1.5", "1/2 cp|0.5", "2|2", "2 cp|2"})
    void d1420_theCountReadsExactly(String dose, double units) {
        assertThat(CdsRuleEngine.parseDoseUnits(dose)).as(dose).isCloseTo(units, offset(0.0001d));
    }

    private static MedicationCatalogItem fiveHundredMg() {
        return MedicationCatalogItem.builder().strength("500").strengthUnit("mg").build();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1} mg")
    @CsvSource(delimiter = '|', value = {
        // the plain dash, an en dash, and spaces
        "2-4 mg|4",
        "2 - 4 mg|4",
        "2–4 mg|4",
        "2 - 4 mg|4",
        "2-4mg PO BID|4",
        "250-500|500",
        // French
        "2 à 4 mg|4",
        "2 a 4 mg|4",
        "de 2 à 4 mg|4",
        "entre 2 et 4 mg|4",
        "2 jusqu'à 4 mg|4",
        "1,5 à 2,5 mg|2.5",
        "0,5 à 1 g|1000",
        "2 ou 3 mg|3",
        // Spanish
        "de 2 a 4 mg|4",
        "entre 2 y 4 mg|4",
        "2 hasta 4 mg|4",
        "1,5-2,5 mg|2.5",
        "2 o 3 mg|3",
        // English
        "2 to 4 mg|4",
        "from 2 to 4 mg|4",
        "between 2 and 4 mg|4",
        "1.5-2.5 mg|2.5",
        "2 or 3 mg|3",
        // both numbers with their unit
        "2 mg - 4 mg|4",
        "500 mg - 1 g|1000",
        "1 g - 500 mg|1000",
        "250 mcg - 500 mcg|0.5",
        // written high end first
        "4-2 mg|4",
    })
    void aRangeOfWeightReadsItsHighEnd(String dosage, double expectedMg) {
        assertThat(CdsRuleEngine.parseDoseMg(dosage, WEIGHT_KG, null)).isCloseTo(expectedMg, offset(0.0001));
        assertThat(CdsRuleEngine.parseDoseMg(dosage)).isNotNull();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1} mg")
    @CsvSource(delimiter = '|', value = {
        "10-15 mg/kg|300",
        "10 à 15 mg/kg|300",
        "10 mg/kg - 15 mg/kg|300",
        "7,5-12,5 mg / kg toutes les 6 h|250",
    })
    void aRangePerKilogramReadsItsHighEndTimesTheWeight(String dosage, double expectedMg) {
        assertThat(CdsRuleEngine.parseDoseMg(dosage, WEIGHT_KG, null)).isCloseTo(expectedMg, offset(0.0001));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1} units, {2} mg")
    @CsvSource(delimiter = '|', value = {
        "1-2 cp|2|1000",
        "1 à 2 comprimés|2|1000",
        "de 1 a 2 comprimidos|2|1000",
        "1 to 2 tablets|2|1000",
        "1 or 2 tablets|2|1000",
        "1 cp - 2 comprimés|2|1000",
        "1,5-2 cp|2|1000",
    })
    void aRangeOfTabletsReadsItsHighEnd(String dosage, double expectedUnits, double expectedMg) {
        assertThat(CdsRuleEngine.parseDoseUnits(dosage)).isCloseTo(expectedUnits, offset(0.0001));
        assertThat(CdsRuleEngine.parseDoseMg(dosage, WEIGHT_KG, fiveHundredMg())).isCloseTo(expectedMg, offset(0.0001));
    }

    @Test
    @DisplayName("a range in a unit with no mg stays unread in mg: it was read as its first number in mg")
    void aRangeInAnotherUnitIsNotMilligrams() {
        assertThat(CdsRuleEngine.parseDoseMg("5-10 ml", WEIGHT_KG, fiveHundredMg())).isNull();
        assertThat(CdsRuleEngine.parseDoseMg("5 ml - 10 ml", WEIGHT_KG, fiveHundredMg())).isNull();
        assertThat(CdsRuleEngine.parseDoseUnits("5-10 ml")).isNull();
    }

    /**
     * The proof for what worked: each of these comes back as the same text, so every reader
     * behind it sees exactly what it saw before. {@code DoseRange.highEnd} is the only change
     * to the readers. A posology, a dose then its frequency, a combination and a time of day
     * are the shapes a range match could have stolen.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "500 mg", "500", "12,5 mg PO BID", "12.5 mg", "0,25 g", "0.25 g", "1 g", "500 mcg", "500 µg",
        "2 cp", "1 comprimé", "2 tablets", "1 comprimido", "1 sachet", "2 gélules", "5 ml", "10 UI", "2 gouttes",
        "15 mg/kg", "15 mg / kg toutes les 6 h", "BID after meals", "si douleur", "",
        // posology
        "1-0-1", "1-1-1", "1 - 0 - 1", "2-0-2 cp", "1–0–1", "0-0-1", "1-1-1-1",
        // a dose, then its frequency or its time
        "500 mg - 2 fois par jour", "500 mg-2x/j", "500 mg - 3/j", "1 cp - 2 fois par jour", "1 cp à 8 h",
        "500 mg a las 8", "500 mg o 8h", "500 mg to 8h", "2 cp a day", "1 g toutes les 8 h", "500 mg q8h",
        "500 mg 2 fois", "1 cp matin et soir",
        // two different measures
        "500 mg - 2 cp", "10 mg/kg - 4 mg", "2 mg/kg - 4 mg/j",
        // a combination, a concentration, a multiplication, a fraction
        "500/125 mg", "80/480 mg", "250 mg/5 ml", "2 x 500 mg", "2x500mg", "3/2 cp", "1/2/3 cp",
    })
    void aSingleDoseComesBackUntouched(String dosage) {
        assertThat(DoseRange.highEnd(dosage)).isSameAs(dosage);
    }

    /** Read at the first number, 1 000 mg was 1 mg: it passed every limit it was over. */
    @ParameterizedTest
    @CsvSource({
        "1 000 mg, 1000", "1\u00a0000 mg, 1000", "1\u202f000 mg, 1000", "1 500 mg PO, 1500",
        "1 000-2 000 mg, 2000", "1 000 000 mg, 1000000",
    })
    void aSpacedThousandIsOneNumber(String dosage, double mg) {
        assertThat(CdsRuleEngine.parseDoseMg(dosage)).isEqualTo(mg, offset(0.001d));
    }

    /** Read at the first number, half a tablet was a whole one. */
    @ParameterizedTest
    @CsvSource({
        "1/2 cp, 0.5", "1 / 2 cp, 0.5", "\u00bd cp, 0.5", "1/4 comprimido, 0.25", "3/4 tablet, 0.75",
        "\u00be cp, 0.75", "1/2-1 cp, 1",
    })
    void aFractionOfATabletIsThatFraction(String dosage, double units) {
        assertThat(CdsRuleEngine.parseDoseUnits(dosage)).isEqualTo(units, offset(0.001d));
    }

    @Test
    void aCombinationStrengthIsNotAFraction() {
        assertThat(DoseRange.highEnd("500/125 mg")).isEqualTo("500/125 mg");
        assertThat(DoseRange.highEnd("80/480 mg")).isEqualTo("80/480 mg");
    }

    @Test
    void noDoseIsNoDose() {
        assertThat(DoseRange.highEnd(null)).isNull();
        assertThat(CdsRuleEngine.parseDoseMg(null, WEIGHT_KG, null)).isNull();
        assertThat(CdsRuleEngine.parseDoseUnits(null)).isNull();
    }

    @Test
    @DisplayName("what follows the range is kept: the frequency reader still sees it")
    void theRestOfTheTextIsKept() {
        assertThat(DoseRange.highEnd("2-4 mg PO BID")).isEqualTo("4 mg PO BID");
        assertThat(DoseRange.highEnd("de 1 à 2 cp par jour")).isEqualTo("2 cp par jour");
    }
}
