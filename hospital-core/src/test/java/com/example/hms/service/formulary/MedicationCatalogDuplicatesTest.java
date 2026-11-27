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

    @Test
    void aBlankNameMatchesNothing() {
        assertThat(MedicationCatalogDuplicates.exact(entry(" ", "TABLET", null, null), entry("", "TABLET", null, null)))
            .isFalse();
    }
}
