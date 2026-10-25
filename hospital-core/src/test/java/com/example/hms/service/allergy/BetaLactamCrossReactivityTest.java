package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D793: the beta-lactam cross-reactivity advisory rule. */
class BetaLactamCrossReactivityTest {

    private static PatientAllergy allergy(String display, boolean active) {
        PatientAllergy a = new PatientAllergy();
        a.setAllergenDisplay(display);
        a.setActive(active);
        return a;
    }

    @Test
    @DisplayName("QA D793: ceftriaxone for a penicillin allergy is a possible cross-reaction")
    void qaCase() {
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen("Ceftriaxone injectable 1 g",
            List.of(allergy("Pénicilline", true)))).contains("Pénicilline");
    }

    @ParameterizedTest(name = "{0} with an allergy to {1}")
    @CsvSource({
        "Céfalexine 500 mg, penicillin",
        "Cefixime, Penicilina",
        "Méropénem 1 g, pénicilline G",
        "Imipenem, amoxicilline",
        "Amoxicilline 1 g, Céphalosporines",
        "Ceftriaxone, céphalosporines",
        "Ampicilline, Amoxicilline",
        "Ceftriaxone, bêta-lactamines",
        "Amoxicilline, beta-lactam antibiotics",
        "Cloxacilline, Imipénem",
    })
    @DisplayName("a beta-lactam the allergy does not name, but may cross-react with, is flagged")
    void crossReactionsAreFlagged(String medication, String allergen) {
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen(medication, List.of(allergy(allergen, true))))
            .contains(allergen);
    }

    @ParameterizedTest(name = "{0} with an allergy to {1}")
    @CsvSource({
        // The allergy check blocks these itself: no advisory on top.
        "Amoxicilline 1 g, Pénicilline",
        "Ceftriaxone 1 g, Ceftriaxone",
        // Not a beta-lactam, or not a beta-lactam allergy.
        "Paracétamol 1 g, Pénicilline",
        "Ciprofloxacine, Pénicilline",
        "Doxycycline, Pénicilline",
        "Ceftriaxone, Sulfamides",
        "Ceftriaxone, Arachide",
    })
    @DisplayName("a direct match (the block's job) or an unrelated pair is not an advisory")
    void othersAreNot(String medication, String allergen) {
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen(medication, List.of(allergy(allergen, true))))
            .isEmpty();
    }

    @Test
    @DisplayName("an inactive allergy, a blank or null entry, and a blank medication are ignored")
    void inactiveAndBlankAreIgnored() {
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen("Ceftriaxone",
            List.of(allergy("Pénicilline", false)))).isEmpty();
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen("Ceftriaxone",
            java.util.Arrays.asList(null, allergy(null, true), allergy("  ", true)))).isEmpty();
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen(" ", List.of(allergy("Pénicilline", true))))
            .isEmpty();
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen("Ceftriaxone", null)).isEmpty();
    }

    @Test
    @DisplayName("the block's class rule: a penicillin allergy covers any penicillin, never a cephalosporin")
    void classMatch() {
        assertThat(BetaLactamCrossReactivity.classMatchedAllergen("Amoxicillin 500 mg",
            List.of(allergy("Penicillin", true)))).contains("Penicillin");
        assertThat(BetaLactamCrossReactivity.classMatchedAllergen("Ceftriaxone",
            List.of(allergy("Penicillin", true)))).isEmpty();
        assertThat(BetaLactamCrossReactivity.classMatchedAllergen("Amoxicillin",
            List.of(allergy("Penicillin", false)))).isEmpty();
    }
}
