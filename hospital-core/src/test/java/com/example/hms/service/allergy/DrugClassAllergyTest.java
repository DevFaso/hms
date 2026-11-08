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

    /**
     * The guard: a drug-class rule lives in this package. The penicillin rule
     * was hand-copied into the prescription check, so the sulfonamide class
     * never reached it; a class stem written as a string literal anywhere else
     * fails.
     */
    @Test
    void noDrugClassRuleIsWrittenOutsideTheAllergyPackage() throws java.io.IOException {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java/com/example/hms");
        java.util.regex.Pattern stem =
            // QA D1183: a brand name (Bactrim, Septra, Augmentin) is class data too.
            java.util.regex.Pattern.compile("\"(penicil|cillin|cilin|sulfa|sulpha|sulfonamid|cotrimox|bactrim|septra|augmentin)");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(main)) {
            for (java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.toString().replace('\\', '/').contains("/service/allergy/")) {
                    continue;
                }
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    String code = line.trim();
                    if (!code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*")
                            && stem.matcher(code.toLowerCase(java.util.Locale.ROOT)).find()) {
                        offenders.add(main.relativize(file) + ": " + code);
                    }
                }
            }
        }
        assertThat(offenders).as("a drug-class allergy rule outside DrugClassAllergy (QA D1149)").isEmpty();
    }

    @Test
    void theCatalogueItemsAtcCodePlacesABrandNameInItsClass() {
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", "J01EE01")).isTrue();
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", " j01ec02 ")).isTrue();
        assertThat(DrugClassAllergy.covers("Sulfamides", "Triméthoprime", "J01EA01"))
            .as("trimethoprim alone is not a sulfonamide").isFalse();
        // QA D1183 (round 22, changed assertion): this pinned the defect - the
        // brand alone, with no ATC code, passed a sulfonamide allergy.
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", null)).isTrue();
    }

    /**
     * QA D1183 (round 22): the brand names clinicians type, against the class
     * allergies as they are recorded, in the three languages. Only the
     * standard equivalences of the brief (Bactrim/Bactrim Forte/Septra,
     * Augmentin) are data; everything else stays a non-match.
     */
    @ParameterizedTest(name = "{0} covers {1}")
    @CsvSource({
        "Sulfamides, Bactrim",
        "Sulfamides, Bactrim forte 960 mg",
        "Sulfamides, BACTRIM FORTE",
        "sulfonamides, Septra DS",
        "Sulfamidas, Bactrim",
        "Cotrimoxazole, Bactrim",
        "Sulfaméthoxazole, Bactrim forte",
        "Pénicilline, Augmentin 1 g",
        "penicillin, Augmentin",
        "Penicilina, augmentin 875/125",
    })
    void aBrandIsReadWithItsGenericNames_D1183(String allergen, String medication) {
        assertThat(DrugClassAllergy.covers(allergen, medication, null)).isTrue();
    }

    @ParameterizedTest(name = "{0} does not cover {1}")
    @CsvSource({
        "Sulfamides, Augmentin",
        "Pénicilline, Bactrim forte",
        "Pénicilline, Bactrimix",
        "Sulfamides, Augmentine-like syrup",
    })
    void aBrandCoversOnlyItsOwnClass_D1183(String allergen, String medication) {
        assertThat(DrugClassAllergy.covers(allergen, medication, null)).isFalse();
    }

    @Test
    void theGenericNamesAreAppendedOnlyForAKnownBrand_D1183() {
        assertThat(DrugClassAllergy.withGenericNames("Bactrim Forte"))
            .isEqualTo("bactrim forte sulfamethoxazole trimethoprim co-trimoxazole");
        assertThat(DrugClassAllergy.withGenericNames("Augmentin")).contains("amoxicilline").contains("clavulanic");
        assertThat(DrugClassAllergy.withGenericNames("Paracétamol 500 mg")).isEqualTo("paracetamol 500 mg");
        assertThat(DrugClassAllergy.withGenericNames(null)).isEmpty();
    }
}
