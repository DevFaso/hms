package com.example.hms.payload.dto.clinical;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1670/D1671 (r44): the checkout's follow-up reason was cut to 1000 without
 * a word, and its instructions and diagnoses were refused at persist in raw
 * English. Each is now refused by the request, naming its field.
 */
class CheckOutRequestLimitsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> refused(CheckOutRequestDTO request) {
        Set<ConstraintViolation<CheckOutRequestDTO>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString() + " " + v.getMessageTemplate())
            .collect(Collectors.toSet());
    }

    @Test
    void theFollowUpReasonIsRefusedPastTheRecallColumnNotCut() {
        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                .reason("r".repeat(501)).build())
            .build();
        assertThat(refused(request)).containsExactly("followUpAppointment.reason {checkout.followUpReason.size}");
        request.getFollowUpAppointment().setReason("r".repeat(500));
        assertThat(refused(request)).isEmpty();
    }

    @Test
    void theInstructionsAreRefusedPast3000WithTheirOwnMessage() {
        CheckOutRequestDTO request = CheckOutRequestDTO.builder().followUpInstructions("i".repeat(3001)).build();
        assertThat(refused(request)).containsExactly("followUpInstructions {checkout.followUpInstructions.size}");
    }

    @Test
    void theDiagnosesAreHeldToTheStoredColumnJoined() {
        String line = "d".repeat(2499);
        CheckOutRequestDTO fits = CheckOutRequestDTO.builder().dischargeDiagnoses(List.of(line, line)).build();
        assertThat(refused(fits)).isEmpty();
        CheckOutRequestDTO over = CheckOutRequestDTO.builder().dischargeDiagnoses(List.of(line, line, "d")).build();
        assertThat(refused(over)).containsExactly("dischargeDiagnosesWithinLimit {checkout.dischargeDiagnoses.size}");
        CheckOutRequestDTO oneLine = CheckOutRequestDTO.builder().dischargeDiagnoses(List.of("d".repeat(5001))).build();
        assertThat(refused(oneLine)).hasSize(2);
    }
}
