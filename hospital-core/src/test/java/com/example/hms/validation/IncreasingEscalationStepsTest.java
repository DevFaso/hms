package com.example.hms.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.controller.HospitalCriticalResultEscalationController.UpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * QA r70 (C5, D1371): the three steps of the critical-result ladder are judged together on the request body, so
 * one refusal names every field, with the required-field refusals.
 */
class IncreasingEscalationStepsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static Set<String> refused(Integer cover, Integer lead, Integer unresolved) {
        return validator.validate(new UpdateRequest(cover, lead, unresolved, null)).stream()
            .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    @Test
    void anIncreasingSetWithinTheRangeIsAccepted() {
        assertThat(refused(15, 30, 60)).isEmpty();
        assertThat(refused(5, 6, 1440)).isEmpty();
    }

    @Test
    void aSetThatDoesNotIncreaseOrLeavesTheRangeNamesTheThreeSteps() {
        for (Integer[] bad : new Integer[][] {{30, 20, 60}, {15, 15, 60}, {4, 30, 60}, {15, 30, 1441}, {1, 1, 1}}) {
            assertThat(refused(bad[0], bad[1], bad[2]))
                .containsExactlyInAnyOrder("coverAfterMinutes", "leadAfterMinutes", "unresolvedAfterMinutes");
        }
    }

    @Test
    void aStepLeftOutIsNamedByItsOwnConstraintOnly() {
        assertThat(refused(15, null, 60)).containsExactly("leadAfterMinutes");
        assertThat(refused(null, null, null))
            .containsExactlyInAnyOrder("coverAfterMinutes", "leadAfterMinutes", "unresolvedAfterMinutes");
    }
}
