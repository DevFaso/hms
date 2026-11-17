package com.example.hms.payload.dto;

import com.example.hms.enums.DischargeDisposition;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1652: 2 001 to 3 000 characters of discharge instructions passed
 * validation and failed on the 2 000-character column as an unnamed data
 * error. The request is now refused on {@code dischargeInstructions} with its
 * own message; 2 000 still passes. The diagnosis has its own 2 000 message.
 */
class AdmissionDischargeTextLimitTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AdmissionDischargeRequestDTO discharge(String instructions, String diagnosis) {
        AdmissionDischargeRequestDTO dto = new AdmissionDischargeRequestDTO();
        dto.setDischargeDisposition(DischargeDisposition.HOME);
        dto.setDischargeSummary("Évolution favorable.");
        dto.setDischargingProviderId(UUID.randomUUID());
        dto.setDischargeInstructions(instructions);
        dto.setDischargeDiagnosis(diagnosis);
        return dto;
    }

    @Test
    void instructionsOverTheColumnAreRefusedOnTheirField_qaD1652() {
        assertThat(validator.validate(discharge("x".repeat(2000), "R50.9"))).isEmpty();

        Set<ConstraintViolation<AdmissionDischargeRequestDTO>> refused =
            validator.validate(discharge("x".repeat(2001), "R50.9"));
        assertThat(refused).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("dischargeInstructions");
            assertThat(v.getMessageTemplate()).isEqualTo("{admissionDischarge.dischargeInstructions.size}");
        });
    }

    @Test
    void diagnosisHasItsOwnLimitMessage_qaD1652() {
        Set<ConstraintViolation<AdmissionDischargeRequestDTO>> refused =
            validator.validate(discharge(null, "x".repeat(2001)));
        assertThat(refused).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("dischargeDiagnosis");
            assertThat(v.getMessageTemplate()).isEqualTo("{admissionDischarge.dischargeDiagnosis.size}");
        });
    }
}
