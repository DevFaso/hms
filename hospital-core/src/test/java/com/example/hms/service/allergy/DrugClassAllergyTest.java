package com.example.hms.service.allergy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1149 (round 21): a drug-class allergy covers the drugs of its class. */
class DrugClassAllergyTest {

    @ParameterizedTest(name = "{0} covers {1}")
    @CsvSource({
        "Sulfamides, Cotrimoxazole 960 mg",
        "Sulfamides, Sulfaméthoxazole-triméthoprime 960 mg",
        "sulfonamides, co-trimoxazole",
        "Sulfamidas, Sulfadiazina",
        "Sulfaméthoxazole, Cotrimoxazole 480 mg",
        "sulfa, Sulfadoxine-pyriméthamine",
        "Pénicilline, Amoxicilline 500 mg",
        "penicillin, ampicillin",
    })
    void aClassAllergyCoversItsDrugs(String allergen, String medication) {
        assertThat(DrugClassAllergy.covers(allergen, medication, null)).isTrue();
    }

    @ParameterizedTest(name = "{0} does not cover {1}")
    @CsvSource({
        "Sulfamides, Sulfate de fer 200 mg",
        "Sulfamides, Morphine sulfate",
        "Sulfamides, Triméthoprime 100 mg",
        "Sulfamides, Amoxicilline",
        "Pénicilline, Ceftriaxone",
        "Arachide, Cotrimoxazole",
    })
    void itCoversNothingElse(String allergen, String medication) {
        assertThat(DrugClassAllergy.covers(allergen, medication, null)).isFalse();
    }

    @Test
    void theCatalogueItemsAtcCodePlacesABrandNameInItsClass() {
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", "J01EE01")).isTrue();
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", " j01ec02 ")).isTrue();
        assertThat(DrugClassAllergy.covers("Sulfamides", "Triméthoprime", "J01EA01"))
            .as("trimethoprim alone is not a sulfonamide").isFalse();
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", null)).isFalse();
    }
}
