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
    void anOverLongReasonIsRefusedAtTheEdgeAndABlankOneByTheService() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            // QA D1267 (round 26): a blank reason is the SERVICE's refusal, in each
            // action's own words (CancelledOrders.requireReason); the shared body
            // no longer says "the reason for the cancellation" for every action.
            assertThat(factory.getValidator().validate(new CloseReasonRequestDTO("  "))).isEmpty();

            Set<ConstraintViolation<CloseReasonRequestDTO>> tooLong =
                factory.getValidator().validate(new CloseReasonRequestDTO("x".repeat(501)));
            assertThat(tooLong).extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly("{validation.reason.size}");

            assertThat(factory.getValidator().validate(new CloseReasonRequestDTO("Vu ailleurs"))).isEmpty();
        }
    }
}
