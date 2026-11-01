package com.example.hms.payload.dto.scheduling;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1157 / D1148 (round 21): the body of the waitlist close, the recall
 * cancel and the directive revoke refuses a blank or an over-long reason at
 * the edge, with the localized keys the portal shows.
 */
class CloseReasonRequestDTOTest {

    @Test
    void aBlankOrTooLongReasonIsRefused() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<CloseReasonRequestDTO>> blank =
                factory.getValidator().validate(new CloseReasonRequestDTO("  "));
            assertThat(blank).extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly("{order.lifecycle.cancelReasonRequired}");

            Set<ConstraintViolation<CloseReasonRequestDTO>> tooLong =
                factory.getValidator().validate(new CloseReasonRequestDTO("x".repeat(501)));
            assertThat(tooLong).extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly("{validation.reason.size}");

            assertThat(factory.getValidator().validate(new CloseReasonRequestDTO("Vu ailleurs"))).isEmpty();
        }
    }
}
