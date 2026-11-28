package com.example.hms.service.formulary;

import com.example.hms.model.medication.FormularySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D4180 (r81): how two catalogue entries are compared; the circuit itself is in the version service's test. */
@DisplayName("Medication catalogue: the same medicine, however it is written")
class MedicationCatalogDuplicatesTest {

    private static FormularySnapshot entry(String name, String form, String strength, String unit) {
        return new FormularySnapshot(name, null, null, null, form, strength, unit, null, null, null, false, true,
            null, false, null, null);
    }

    @ParameterizedTest(name = "« {0} » {1} {2}{3} is « {4} » {5} {6}{7}")
    @CsvSource(nullValues = "-", value = {
        "Céfixime,            TABLET, 200, mg,    cefixime,          tablet,  200 MG, -",
        "Paracétamol 500 mg,  TABLET, -,   -,     PARACETAMOL  500mg, TABLET,  -,      -",
        "Amoxicilline,        SYRUP,  '125 mg/5 mL', -, amoxicilline, SYRUP,   125mg/5ml, -",
        "Morphine,            INJECTION, '0,5', mg, Morphine,        INJECTION, 0.5,   mg",
    })
    void theSameNameFormAndStrengthAreOneMedicine(String name, String form, String strength, String unit,
                                                  String otherName, String otherForm, String otherStrength,
                                                  String otherUnit) {
        assertThat(MedicationCatalogDuplicates.exact(entry(name, form, strength, unit),
            entry(otherName, otherForm, otherStrength, otherUnit))).isTrue();
    }

    @Test
    void anotherStrengthOrAnotherFormIsAnotherEntry_thatOnlyLooksLikeIt() {
        FormularySnapshot tablet200 = entry("Céfixime", "TABLET", "200", "mg");
        FormularySnapshot tablet400 = entry("Céfixime", "TABLET", "400", "mg");
        FormularySnapshot syrup = entry("Céfixime", "SYRUP", "200", "mg");
        assertThat(MedicationCatalogDuplicates.exact(tablet200, tablet400)).isFalse();
        assertThat(MedicationCatalogDuplicates.exact(tablet200, syrup)).isFalse();
        assertThat(MedicationCatalogDuplicates.similar(tablet200, tablet400)).isTrue();
        assertThat(MedicationCatalogDuplicates.similar(tablet200, syrup)).isTrue();
    }

    @Test
    void aStrengthWrittenInTheNameDoesNotHideTheSameMedicine() {
        // « Paracétamol 500 mg » beside « Paracétamol » with its strength in its own field.
        FormularySnapshot inTheName = entry("Paracétamol 500 mg", "TABLET", null, null);
        FormularySnapshot inItsField = entry("Paracétamol", "TABLET", "500", "mg");
        assertThat(MedicationCatalogDuplicates.exact(inTheName, inItsField)).isFalse();
        assertThat(MedicationCatalogDuplicates.similar(inTheName, inItsField)).isTrue();
        assertThat(MedicationCatalogDuplicates.baseNameKey("Sirop Paracétamol 120 mg/5 mL")).isEqualTo("sirop paracetamol");
    }

    @Test
    void anotherMedicineIsNeitherTheSameNorSimilar_andANumberInAWordIsNotAStrength() {
        FormularySnapshot cefixime = entry("Céfixime", "TABLET", "200", "mg");
        FormularySnapshot ceftriaxone = entry("Ceftriaxone", "TABLET", "200", "mg");
        assertThat(MedicationCatalogDuplicates.exact(cefixime, ceftriaxone)).isFalse();
        assertThat(MedicationCatalogDuplicates.similar(cefixime, ceftriaxone)).isFalse();
        assertThat(MedicationCatalogDuplicates.baseNameKey("Vitamine B12")).isEqualTo("vitamine b12");
        assertThat(MedicationCatalogDuplicates.similar(entry("Vitamine B12", null, null, null),
            entry("Vitamine B6", null, null, null))).isFalse();
    }

    /**
     * Review of #1383 (F1): the strength at the end of a name was found by a pattern that backtracked (about four
     * seconds at 500 characters of digit groups). The comparison is linear: a name far longer than any column,
     * made of the shape that defeated the pattern, is compared in a few milliseconds.
     */
    @Test
    void aNameOfThousandsOfDigitGroups_isComparedInLinearTime() {
        String hostile = "Paracetamol " + "1 ".repeat(2500) + "x";
        assertThat(hostile.length()).isGreaterThan(5000);
        FormularySnapshot proposed = entry(hostile, "TABLET", null, null);
        FormularySnapshot existing = entry("Paracétamol 500 mg", "TABLET", null, null);
        // Warm-up, so the measure is the comparison and not class loading.
        MedicationCatalogDuplicates.similar(proposed, existing);

        // The fastest of five runs, so a busy build machine does not decide the result; the pattern this
        // replaces took seconds on a tenth of this length.
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            MedicationCatalogDuplicates.similar(proposed, existing);
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
        }

        assertThat(best).as("one comparison of a 5,000-character name, in ms").isLessThan(100);
        // And it still reads a strength: the numbers at the end of a name are set aside, the last word is not.
        assertThat(MedicationCatalogDuplicates.baseNameKey("Paracetamol " + "1 ".repeat(2500) + "mg"))
            .isEqualTo("paracetamol");
        assertThat(MedicationCatalogDuplicates.baseNameKey(hostile)).endsWith(" x");
    }

    @Test
    void aBlankNameMatchesNothing() {
        assertThat(MedicationCatalogDuplicates.exact(entry(" ", "TABLET", null, null), entry("", "TABLET", null, null)))
            .isFalse();
    }
}
