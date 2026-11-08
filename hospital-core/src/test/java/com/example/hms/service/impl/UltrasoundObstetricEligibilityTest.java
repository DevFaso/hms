package com.example.hms.service.impl;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.utility.ObstetricEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** QA D1538 (round 40): a prenatal scan for a man or a 6-year-old was accepted (201). */
class UltrasoundObstetricEligibilityTest {

    private static Patient patient(String gender, Integer ageYears) {
        Patient p = new Patient();
        p.setGender(gender);
        if (ageYears != null) {
            p.setDateOfBirth(LocalDate.now().minusYears(ageYears).minusDays(1));
        }
        return p;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ROUTINE", "routine"})
    void aRoutineScanForAManIsRefusedOnThePatient(String priority) {
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireObstetricPatient(patient("MALE", 56), priority))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("patientId");
                assertThat(e.getMessageKey()).isEqualTo(ObstetricEligibility.MESSAGE_KEY);
            });
    }

    @Test
    void aRoutineScanForASixYearOldIsRefused() {
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireObstetricPatient(patient("FEMALE", 6), "ROUTINE"))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo(ObstetricEligibility.TOO_YOUNG_MESSAGE_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAT", "URGENT", " stat "})
    void anEmergencyScanIsNeverRefused(String priority) {
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatient(patient("MALE", 6), priority))
            .doesNotThrowAnyException();
    }

    @Test
    void aWomanAndAnUnknownRecordAreLetThrough() {
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatient(patient("FEMALE", 28), "ROUTINE"))
            .doesNotThrowAnyException();
        assertThatCode(() -> UltrasoundServiceImpl.requireObstetricPatient(patient(null, null), "ROUTINE"))
            .doesNotThrowAnyException();
    }
}
