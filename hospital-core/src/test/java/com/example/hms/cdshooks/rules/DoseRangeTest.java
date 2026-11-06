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
        "500/125 mg", "80/480 mg", "250 mg/5 ml", "2 x 500 mg", "2x500mg", "1/2 cp",
    })
    void aSingleDoseComesBackUntouched(String dosage) {
        assertThat(DoseRange.highEnd(dosage)).isSameAs(dosage);
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
