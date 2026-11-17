package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("D1403 + D1414: a birth date in the past and within 130 years, a name that is not only digits")
class PatientIdentityRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void theBirthDateLimits() {
        assertThat(PatientIdentityRules.birthDateProblem(TODAY.plusDays(1), TODAY)).isEqualTo("patient.dateOfBirth.notPast");
        assertThat(PatientIdentityRules.birthDateProblem(TODAY, TODAY)).isEqualTo("patient.dateOfBirth.notPast");
        assertThat(PatientIdentityRules.birthDateProblem(TODAY.minusDays(1), TODAY)).isNull();
        assertThat(PatientIdentityRules.birthDateProblem(TODAY.minusYears(130), TODAY)).isNull();
        assertThat(PatientIdentityRules.birthDateProblem(TODAY.minusYears(130).minusDays(1), TODAY))
            .isEqualTo("patient.dateOfBirth.impossible");
        assertThat(PatientIdentityRules.birthDateProblem(LocalDate.of(1800, 1, 1), TODAY))
            .isEqualTo("patient.dateOfBirth.impossible");
    }

    @Test
    void aStoredDateSentBackIsNotAChangeAndIsNotJudged() {
        LocalDate stored = LocalDate.of(1800, 1, 1);
        assertThatCode(() -> PatientIdentityRules.requirePossibleBirthDate(stored, stored)).doesNotThrowAnyException();
        assertThatCode(() -> PatientIdentityRules.requirePossibleBirthDate(null, stored)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PatientIdentityRules.requirePossibleBirthDate(LocalDate.of(1801, 1, 1), stored))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(e -> assertThat(((FieldBusinessException) e).getFieldErrors()).containsKey("dateOfBirth"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", " 12345 ", "123 45", "12-34", "٣٤٥"})
    void namesMadeOfDigits(String name) {
        assertThat(PatientIdentityRules.isMadeOfDigits(name)).isTrue();
        assertThatThrownBy(() -> PatientIdentityRules.requireNameWithLetters("lastName", name, null))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(e -> assertThat(((FieldBusinessException) e).getFieldErrors()).containsKey("lastName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1", "Awa", "Marie-Noëlle", "O'Brien", "-", "N/A", "", "   ", "Kaboré 2", "Ndèye"})
    void namesThatStayAccepted(String name) {
        assertThat(PatientIdentityRules.isMadeOfDigits(name)).isFalse();
        assertThatCode(() -> PatientIdentityRules.requireNameWithLetters("lastName", name, null)).doesNotThrowAnyException();
    }

    @Test
    void aStoredDigitNameSentBackIsNotAChange() {
        assertThatCode(() -> PatientIdentityRules.requireNameWithLetters("lastName", " 12345 ", "12345")).doesNotThrowAnyException();
        assertThatThrownBy(() -> PatientIdentityRules.requireNameWithLetters("lastName", "54321", "12345"))
            .isInstanceOf(FieldBusinessException.class);
    }
}
