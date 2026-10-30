package com.example.hms.service.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1077 (round 18): measures that do not apply to the patient are refused by the server too. */
class PatientMeasureRulesTest {

    private static Patient patient(String gender, LocalDate dob) {
        Patient p = new Patient();
        p.setGender(gender);
        p.setDateOfBirth(dob);
        return p;
    }

    @Test
    void aFundalHeightIsRefusedForAMan() {
        for (String male : new String[] {"MALE", "M", "male"}) {
            assertThatThrownBy(() -> PatientMeasureRules.requireFundalHeightApplicable(patient(male, null), 28))
                .isInstanceOf(BusinessException.class).hasMessage("vitals.fundalHeight.notApplicable");
        }
    }

    @Test
    void aFundalHeightIsKeptForAWomanAnUnknownSexOrNoValue() {
        assertThatCode(() -> PatientMeasureRules.requireFundalHeightApplicable(patient("FEMALE", null), 28)).doesNotThrowAnyException();
        assertThatCode(() -> PatientMeasureRules.requireFundalHeightApplicable(patient(null, null), 28)).doesNotThrowAnyException();
        assertThatCode(() -> PatientMeasureRules.requireFundalHeightApplicable(patient("MALE", null), null)).doesNotThrowAnyException();
    }

    @Test
    void aHeadCircumferenceIsAChildsMeasure() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        assertThatThrownBy(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("MALE", today.minusYears(65)), 56.0, today))
            .isInstanceOf(BusinessException.class).hasMessage("vitals.headCircumference.notApplicable");
        assertThatThrownBy(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("F", today.minusYears(20)), 54.0, today))
            .isInstanceOf(BusinessException.class);
        assertThatCode(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("F", today.minusYears(19).minusDays(364)), 54.0, today)).doesNotThrowAnyException();
        assertThatCode(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("F", null), 54.0, today)).doesNotThrowAnyException();
    }
}
