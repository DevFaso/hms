package com.example.hms.service.allergy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAllergyTextTest {

    @Test
    @DisplayName("splits on the separators people type, in French and English")
    void splitsOnTypedSeparators() {
        assertThat(LegacyAllergyText.tokens("Pénicilline, sulfamides; arachide / latex et iode and aspirin"))
            .containsExactly("Pénicilline", "sulfamides", "arachide", "latex", "iode", "aspirin");
        assertThat(LegacyAllergyText.tokens("Peanuts\nGarlic\r\nShellfish."))
            .containsExactly("Peanuts", "Garlic", "Shellfish");
    }

    @Test
    @DisplayName("does not split inside words that contain 'et' or 'and'")
    void keepsWordsContainingTheConjunctions() {
        assertThat(LegacyAllergyText.tokens("Bétadine, diète sans gluten, Sandostatine"))
            .containsExactly("Bétadine", "diète sans gluten", "Sandostatine");
    }

    @Test
    @DisplayName("de-duplicates case-insensitively, keeping the first spelling")
    void deduplicates() {
        assertThat(LegacyAllergyText.tokens("Peanuts, peanuts, PEANUTS, garlic"))
            .containsExactly("Peanuts", "garlic");
    }

    @Test
    @DisplayName("a statement of no allergies yields no allergen")
    void nonePhrasesYieldNothing() {
        for (String none : new String[] {"aucune", "Aucune allergie connue", "NKDA", "none", "Pas d'allergie", "néant", "-", "ninguna"}) {
            assertThat(LegacyAllergyText.tokens(none)).as(none).isEmpty();
            assertThat(LegacyAllergyText.statesNone(none)).as(none).isTrue();
        }
        assertThat(LegacyAllergyText.statesNone("  ")).isFalse();
        assertThat(LegacyAllergyText.statesNone(null)).isFalse();
        assertThat(LegacyAllergyText.statesNone("Peanuts")).isFalse();
    }

    @Test
    @DisplayName("a none phrase mixed with a real allergen keeps only the allergen")
    void nonePhraseAmongRealTokensIsDropped() {
        assertThat(LegacyAllergyText.tokens("aucune, Pénicilline")).containsExactly("Pénicilline");
    }

    @Test
    @DisplayName("blank and null yield nothing; a token longer than the column is cut")
    void edges() {
        assertThat(LegacyAllergyText.tokens(null)).isEmpty();
        assertThat(LegacyAllergyText.tokens("   ")).isEmpty();
        String longToken = "x".repeat(400);
        assertThat(LegacyAllergyText.tokens(longToken)).singleElement()
            .satisfies(t -> assertThat(t).hasSize(LegacyAllergyText.MAX_DISPLAY));
    }
}
