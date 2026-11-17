package com.example.hms.payload.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** D44: French department names carry accents and apostrophes. */
class DepartmentRequestDTONameValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private long nameViolations(String name) {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName(name);
        return validator.validateProperty(dto, "name").size();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pédiatrie", "Gynécologie-Obstétrique", "Soins d'urgence", "Médecine générale 2", "Soins d’urgence", "O.R.L."})
    void acceptsAccentedNamesAndApostrophes(String name) {
        assertThat(nameViolations(name)).isZero();
    }

    @Test
    void stillRejectsMarkupCharacters() {
        assertThat(nameViolations("<script>")).isPositive();
        assertThat(nameViolations("Dept; DROP")).isPositive();
    }
}
