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
            java.util.regex.Pattern.compile("\"(penicil|cillin|cilin|sulfa|sulpha|sulfonamid|cotrimox)");
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
        assertThat(DrugClassAllergy.covers("Sulfamides", "Bactrim forte", null)).isFalse();
    }
}
