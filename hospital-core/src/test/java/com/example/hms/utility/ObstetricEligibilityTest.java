package com.example.hms.utility;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObstetricEligibilityTest {

    private static Patient withGender(String gender) {
        Patient patient = new Patient();
        patient.setGender(gender);
        return patient;
    }

    @ParameterizedTest
    @ValueSource(strings = {"MALE", "male", " M ", "Homme", "masculin", "H"})
    void refusesValuesThatMeanMale(String gender) {
        Patient patient = withGender(gender);
        assertThat(ObstetricEligibility.isRecordedMale(patient)).isTrue();
        assertThatThrownBy(() -> ObstetricEligibility.requireObstetricEligible(patient))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"FEMALE", "F", "femme", "OTHER", "UNKNOWN"})
    void letsFemaleUnknownAndMissingThrough(String gender) {
        assertThatCode(() -> ObstetricEligibility.requireObstetricEligible(withGender(gender)))
            .doesNotThrowAnyException();
    }

    @Test
    void aMissingPatientIsNotRecordedMale() {
        assertThat(ObstetricEligibility.isRecordedMale(null)).isFalse();
    }

    /* D413 (D46 regression): a labour episode was accepted for a 5-year-old. */

    private static Patient bornYearsAgo(int years) {
        Patient patient = withGender("FEMALE");
        patient.setDateOfBirth(java.time.LocalDate.now().minusYears(years).minusDays(1));
        return patient;
    }

    @Test
    void d413_refusesAChildOfFive() {
        Patient child = bornYearsAgo(5);
        assertThatThrownBy(() -> ObstetricEligibility.requireObstetricEligible(child))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void d413_acceptsAnAdolescentAndAWoman() {
        assertThatCode(() -> ObstetricEligibility.requireObstetricEligible(bornYearsAgo(15)))
            .doesNotThrowAnyException();
        assertThatCode(() -> ObstetricEligibility.requireObstetricEligible(bornYearsAgo(30)))
            .doesNotThrowAnyException();
    }

    @Test
    void d413_aMissingBirthDateIsLetThrough() {
        assertThat(ObstetricEligibility.isRecordedChild(withGender("FEMALE"))).isFalse();
    }
}
