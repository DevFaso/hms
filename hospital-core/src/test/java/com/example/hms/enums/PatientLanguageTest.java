package com.example.hms.enums;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PatientLanguage")
class PatientLanguageTest {

    @Test
    @DisplayName("reads the spellings the free-text field actually collected")
    void interpretsFreeText() {
        // This was a VARCHAR(50) text input on the medical-history tab since
        // V1, so the stored values are whatever a person typed. Treating
        // "Francais" and "Français" as two different unknowns would send the
        // default to patients who had answered plainly.
        assertThat(PatientLanguage.fromFreeText("French")).contains(PatientLanguage.FRENCH);
        assertThat(PatientLanguage.fromFreeText("francais")).contains(PatientLanguage.FRENCH);
        assertThat(PatientLanguage.fromFreeText("Français")).contains(PatientLanguage.FRENCH);
        assertThat(PatientLanguage.fromFreeText("  FR  ")).contains(PatientLanguage.FRENCH);
        assertThat(PatientLanguage.fromFreeText("English")).contains(PatientLanguage.ENGLISH);
        assertThat(PatientLanguage.fromFreeText("Anglais")).contains(PatientLanguage.ENGLISH);
        assertThat(PatientLanguage.fromFreeText("Español")).contains(PatientLanguage.SPANISH);
    }

    @Test
    @DisplayName("recognises the local languages even though nothing can send them")
    void interpretsLocalLanguages() {
        // Recordable on purpose. A field that cannot express "this patient
        // reads Moore" can never produce the evidence that would justify
        // commissioning a Moore bundle.
        assertThat(PatientLanguage.fromFreeText("Moore")).contains(PatientLanguage.MOORE);
        assertThat(PatientLanguage.fromFreeText("Mooré")).contains(PatientLanguage.MOORE);
        assertThat(PatientLanguage.fromFreeText("bambara")).contains(PatientLanguage.BAMBARA);
        assertThat(PatientLanguage.fromFreeText("Dioula")).contains(PatientLanguage.DIOULA);
    }

    @Test
    @DisplayName("returns nothing rather than guessing")
    void doesNotGuess() {
        // A wrong language is worse than the hospital's configured default,
        // which at least somebody chose.
        assertThat(PatientLanguage.fromFreeText(null)).isEmpty();
        assertThat(PatientLanguage.fromFreeText("   ")).isEmpty();
        assertThat(PatientLanguage.fromFreeText("Klingon")).isEmpty();
        assertThat(PatientLanguage.fromFreeText("fr-CA-x-nonsense")).isEmpty();
    }

    @Test
    @DisplayName("only the languages with bundles claim to have one")
    void bundleFlagMatchesTheBundlesOnDisk() {
        // messages_fr / _en / _es are the only bundles in the repo. If someone
        // adds messages_mos.properties they must flip this flag too, and this
        // test is where that is stated.
        assertThat(PatientLanguage.FRENCH.hasMessageBundle()).isTrue();
        assertThat(PatientLanguage.ENGLISH.hasMessageBundle()).isTrue();
        assertThat(PatientLanguage.SPANISH.hasMessageBundle()).isTrue();
        assertThat(PatientLanguage.BAMBARA.hasMessageBundle()).isFalse();
        assertThat(PatientLanguage.DIOULA.hasMessageBundle()).isFalse();
        assertThat(PatientLanguage.MOORE.hasMessageBundle()).isFalse();
    }

    @Test
    @DisplayName("every value carries a distinct, usable language tag")
    void tagsAreDistinctAndParseable() {
        assertThat(PatientLanguage.values())
            .extracting(PatientLanguage::languageTag)
            .doesNotHaveDuplicates()
            .allSatisfy(tag -> assertThat(Locale.forLanguageTag(tag).getLanguage()).isNotEmpty());
    }
}
