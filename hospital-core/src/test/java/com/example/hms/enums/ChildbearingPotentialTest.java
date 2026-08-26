package com.example.hms.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Who the platelet Rh restriction protects (haematologist sign-off,
 * 2026-08-25): females under 55, not males or older females.
 *
 * <p>The rejection cases carry the weight. {@code gender} is a free-text
 * column with no canonical vocabulary anywhere in the codebase, so
 * unrecognised values are the normal case rather than the exceptional one,
 * and every one of them has to protect rather than permit.
 */
class ChildbearingPotentialTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @ParameterizedTest(name = "{0} born {1} -> {2}")
    @CsvSource({
        // Females below the threshold are protected.
        "F,       1995-01-01, YES",
        "female,  1995-01-01, YES",
        "FEMALE,  1995-01-01, YES",
        "Femme,   1995-01-01, YES",
        // ...and at or over it, exempt.
        "F,       1960-01-01, NO",
        "female,  1971-08-25, NO",
        // Males are exempt at any age.
        "M,       1995-01-01, NO",
        "male,    2005-01-01, NO",
        "Homme,   1940-01-01, NO",
    })
    void recognisedValues(String gender, LocalDate dob, ChildbearingPotential expected) {
        assertThat(ChildbearingPotential.of(gender, dob, TODAY)).isEqualTo(expected);
    }

    @Test
    void theThresholdIsInclusive() {
        // Exactly 55 today is exempt; one day short is not. An off-by-one
        // here silently removes protection from a whole birth-year.
        LocalDate turns55Today = TODAY.minusYears(55);
        assertThat(ChildbearingPotential.of("F", turns55Today, TODAY))
            .isEqualTo(ChildbearingPotential.NO);
        assertThat(ChildbearingPotential.of("F", turns55Today.plusDays(1), TODAY))
            .isEqualTo(ChildbearingPotential.YES);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown", "other", "X", "n/a", "not recorded", "1"})
    void anUnrecognisedGenderIsUnknownAndThereforeProtected(String gender) {
        ChildbearingPotential result =
            ChildbearingPotential.of(gender, LocalDate.of(1995, 1, 1), TODAY);

        assertThat(result).isEqualTo(ChildbearingPotential.UNKNOWN);
        assertThat(result.requiresRhProtection()).isTrue();
    }

    @Test
    void aKnownFemaleWithNoDateOfBirthCannotClaimTheExemption() {
        // The exemption is an age claim. Without an age there is no claim.
        assertThat(ChildbearingPotential.of("F", null, TODAY))
            .isEqualTo(ChildbearingPotential.UNKNOWN);
    }

    @Test
    void onlyNoRemovesTheRestriction() {
        assertThat(ChildbearingPotential.YES.requiresRhProtection()).isTrue();
        assertThat(ChildbearingPotential.UNKNOWN.requiresRhProtection()).isTrue();
        assertThat(ChildbearingPotential.NO.requiresRhProtection()).isFalse();
    }
}
