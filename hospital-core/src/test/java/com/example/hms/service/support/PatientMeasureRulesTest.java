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
        for (String male : new String[] {"MALE", "M", "male", "Homme", "masculin", "H"}) {
            var man = patient(male, null);
            assertThatThrownBy(() -> PatientMeasureRules.requireFundalHeightApplicable(man, 28))
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
        var adult = patient("MALE", today.minusYears(65));
        assertThatThrownBy(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(adult, 56.0, today))
            .isInstanceOf(BusinessException.class).hasMessage("vitals.headCircumference.notApplicable");
        var twenty = patient("F", today.minusYears(20));
        assertThatThrownBy(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(twenty, 54.0, today))
            .isInstanceOf(BusinessException.class);
        assertThatCode(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("F", today.minusYears(19).minusDays(364)), 54.0, today)).doesNotThrowAnyException();
        assertThatCode(() -> PatientMeasureRules.requireHeadCircumferenceApplicable(
                patient("F", null), 54.0, today)).doesNotThrowAnyException();
    }

    /** QA D1077 (round 19): the one check every vitals write runs before the save. */
    @Test
    void theVitalSignRuleChecksBothMeasuresAgainstTheRowsPatient() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        com.example.hms.model.PatientVitalSign adultRow = com.example.hms.model.PatientVitalSign.builder()
            .patient(patient("MALE", today.minusYears(67))).headCircumferenceCm(56.0).build();
        assertThatThrownBy(() -> PatientMeasureRules.requireVitalSignApplicable(adultRow, null, today))
            .isInstanceOf(BusinessException.class).hasMessage("vitals.headCircumference.notApplicable");
        com.example.hms.model.PatientVitalSign manRow = com.example.hms.model.PatientVitalSign.builder()
            .patient(patient("MALE", today.minusYears(30))).build();
        assertThatThrownBy(() -> PatientMeasureRules.requireVitalSignApplicable(manRow, 28, today))
            .isInstanceOf(BusinessException.class).hasMessage("vitals.fundalHeight.notApplicable");
        com.example.hms.model.PatientVitalSign childRow = com.example.hms.model.PatientVitalSign.builder()
            .patient(patient("FEMALE", today.minusYears(2))).headCircumferenceCm(47.0).build();
        assertThatCode(() -> PatientMeasureRules.requireVitalSignApplicable(childRow, 20, today)).doesNotThrowAnyException();
    }
}
