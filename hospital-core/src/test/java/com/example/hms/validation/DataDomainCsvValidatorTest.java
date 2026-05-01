package com.example.hms.validation;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataDomainCsvValidator")
class DataDomainCsvValidatorTest {

    private DataDomainCsvValidator validator;
    private ConstraintValidatorContext context;
    private ConstraintViolationBuilder builder;

    @BeforeEach
    void setUp() {
        validator = new DataDomainCsvValidator();
        context = mock(ConstraintValidatorContext.class);
        builder = mock(ConstraintViolationBuilder.class);
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    }

    @Test void nullIsValid() {
        assertThat(validator.isValid(null, context)).isTrue();
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test void blankIsValid() {
        assertThat(validator.isValid("   ", context)).isTrue();
    }

    @Test void emptyIsValid() {
        assertThat(validator.isValid("", context)).isTrue();
    }

    @Test void validSingleTokenIsValid() {
        assertThat(validator.isValid("PRESCRIPTIONS", context)).isTrue();
    }

    @Test void validMixedCaseCsvIsValid() {
        assertThat(validator.isValid("prescriptions, LAB_RESULTS , Encounters", context)).isTrue();
    }

    @Test void unknownTokenIsInvalidAndAddsCustomViolation() {
        assertThat(validator.isValid("PRESCRIPTIONS,FOO_BAR", context)).isFalse();
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
            org.mockito.ArgumentMatchers.contains("'FOO_BAR'"));
        verify(builder).addConstraintViolation();
    }

    @Test void messageDoesNotLeakEnumValueOfInternals() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        assertThat(validator.isValid("BOGUS", context)).isFalse();
        verify(context).buildConstraintViolationWithTemplate(captor.capture());
        // Must not contain the leaked Enum.valueOf shape "No enum constant ..."
        assertThat(captor.getValue()).doesNotContain("No enum constant");
        assertThat(captor.getValue()).contains("Allowed values:");
    }
}
