package com.example.hms.service.support;

import com.example.hms.cdshooks.rules.PediatricDoseRule;
import com.example.hms.enums.RhFactor;
import com.example.hms.service.support.EmergencyReleasePolicy.OPositiveStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("who may receive O positive on an emergency release")
class EmergencyReleasePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final LocalDate ADULT = TODAY.minusYears(PediatricDoseRule.PEDIATRIC_AGE_THRESHOLD_YEARS);
    private static final LocalDate MINOR = ADULT.plusDays(1);

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
        "MALE",
        "Male",
        "m",
        "homme",
        "Masculin",
        " male ",
    })
    @DisplayName("an adult male on the record, untyped or Rh positive, clean screen: no extra step")
    void adultMale(String gender) {
        assertThat(EmergencyReleasePolicy.oPositiveStep(gender, ADULT, TODAY, null, false)).isEqualTo(OPositiveStep.NONE);
        assertThat(EmergencyReleasePolicy.oPositiveStep(gender, ADULT, TODAY, RhFactor.POSITIVE, false)).isEqualTo(OPositiveStep.NONE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
        "FEMALE", "f", "femme", "féminin", "OTHER", "x", "''", "null",
    })
    @DisplayName("female or unknown sex: O negative first, whatever the age, 55 and 90 included")
    void everyFemaleOrUnknownSex(String gender) {
        for (int age : new int[] {1, 17, 18, 30, 54, 55, 70, 90}) {
            assertThat(EmergencyReleasePolicy.oPositiveStep(gender, TODAY.minusYears(age), TODAY, null, false))
                .as("age %d", age).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
        }
    }

    @Test
    @DisplayName("a minor, or a male whose age the record does not prove, is not an adult male")
    void aMinorOrAnUnprovenAge() {
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", MINOR, TODAY, null, false)).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", null, TODAY, null, false)).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", TODAY.plusDays(1), TODAY, null, false)).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
    }

    @Test
    @DisplayName("typed Rh negative, of any sex: O negative first")
    void typedRhNegative() {
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", ADULT, TODAY, RhFactor.NEGATIVE, false)).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
        assertThat(EmergencyReleasePolicy.oPositiveStep("FEMALE", ADULT, TODAY, RhFactor.NEGATIVE, false)).isEqualTo(OPositiveStep.O_NEGATIVE_FIRST);
    }

    @Test
    @DisplayName("a positive screen on an adult male asks for the decision and does not block")
    void positiveScreen() {
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", ADULT, TODAY, RhFactor.POSITIVE, true)).isEqualTo(OPositiveStep.DECISION);
        assertThat(EmergencyReleasePolicy.oPositiveStep("MALE", ADULT, TODAY, null, true)).isEqualTo(OPositiveStep.DECISION);
    }
}
