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
}
