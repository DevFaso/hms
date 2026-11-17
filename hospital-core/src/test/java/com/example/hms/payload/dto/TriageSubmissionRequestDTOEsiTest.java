package com.example.hms.payload.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D511: the body of POST /encounters/{id}/triage is {@code @Valid}; a
 * triage with vitals and a complaint but no ESI score was accepted (200).
 */
class TriageSubmissionRequestDTOEsiTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aTriageWithoutEsiIsInvalid() {
        TriageSubmissionRequestDTO body = TriageSubmissionRequestDTO.builder()
            .temperatureCelsius(38.5).heartRateBpm(96).chiefComplaint("Fièvre").build();

        Set<ConstraintViolation<TriageSubmissionRequestDTO>> violations = validator.validate(body);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("esiScore");
    }

    @Test
    void aTriageWithAnEsiIsValid() {
        TriageSubmissionRequestDTO body = TriageSubmissionRequestDTO.builder()
            .temperatureCelsius(38.5).heartRateBpm(96).chiefComplaint("Fièvre").esiScore(3).build();

        assertThat(validator.validate(body)).isEmpty();
    }
}
