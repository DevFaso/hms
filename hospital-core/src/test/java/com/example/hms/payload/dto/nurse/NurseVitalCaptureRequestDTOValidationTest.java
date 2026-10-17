package com.example.hms.payload.dto.nurse;

import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D29: the nurse station saved 45 °C, a heart rate of 400 and an SpO2 of 150 %. */
class NurseVitalCaptureRequestDTOValidationTest {

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

    private static Set<String> invalidFields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void rejectsTheImpossibleValuesTheQaRunSaved() {
        NurseVitalCaptureRequestDTO request = NurseVitalCaptureRequestDTO.builder()
            .temperatureCelsius(45.0)
            .heartRateBpm(400)
            .spo2Percent(150)
            .build();

        assertThat(invalidFields(validator.validate(request)))
            .containsExactlyInAnyOrder("temperatureCelsius", "heartRateBpm", "spo2Percent");
    }

    @Test
    void rejectsEveryOtherReadingOutsideItsBounds() {
        NurseVitalCaptureRequestDTO request = NurseVitalCaptureRequestDTO.builder()
            .temperatureCelsius(20.0)
            .respiratoryRateBpm(2)
            .systolicBpMmHg(350)
            .diastolicBpMmHg(10)
            .bloodGlucoseMgDl(900)
            .weightKg(500.0)
            .heightCm(300.0)
            .headCircumferenceCm(5.0)
            .build();

        assertThat(invalidFields(validator.validate(request))).containsExactlyInAnyOrder(
            "temperatureCelsius", "respiratoryRateBpm", "systolicBpMmHg", "diastolicBpMmHg",
            "bloodGlucoseMgDl", "weightKg", "heightCm", "headCircumferenceCm");
    }

    @Test
    void acceptsAbnormalButRealReadingsAtTheBounds() {
        NurseVitalCaptureRequestDTO request = NurseVitalCaptureRequestDTO.builder()
            .temperatureCelsius(44.0)
            .heartRateBpm(20)
            .respiratoryRateBpm(80)
            .systolicBpMmHg(40)
            .diastolicBpMmHg(200)
            .spo2Percent(70)
            .bloodGlucoseMgDl(20)
            .weightKg(0.2)
            .heightCm(20.0)
            .headCircumferenceCm(70.0)
            .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void theOtherVitalEntryPointsBoundTemperatureToo() {
        assertThat(invalidFields(validator.validate(
            PatientVitalSignRequestDTO.builder().temperatureCelsius(45.0).build())))
            .containsExactly("temperatureCelsius");
        assertThat(invalidFields(validator.validate(
            TriageSubmissionRequestDTO.builder().temperatureCelsius(45.0).esiScore(3).build())))
            .contains("temperatureCelsius");
    }
}
