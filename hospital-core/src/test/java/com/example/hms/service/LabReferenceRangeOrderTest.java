package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D287: inverted reference and critical ranges were saved. */
class LabReferenceRangeOrderTest {

    private static LabTestReferenceRangeDTO range(Double min, Double max, Double critLow, Double critHigh) {
        return LabTestReferenceRangeDTO.builder().minValue(min).maxValue(max)
            .criticalLow(critLow).criticalHigh(critHigh).build();
    }

    @Test
    void anInvertedReferenceRangeIsRefusedOnEveryWritePath() {
        List<LabTestReferenceRangeDTO> inverted = List.of(range(5.0, 3.5, null, null));
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(inverted))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.inverted");
    }

    @Test
    void invertedOrInsideCriticalLimitsAreRefused() {
        // QA D896: each refusal names the limit it is about.
        List<LabTestReferenceRangeDTO> ranges1 = List.of(range(3.5, 5.0, 6.5, 2.5));
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(ranges1))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.criticalHigh.belowCriticalLow")
            .hasFieldOrPropertyWithValue("field", "[0].criticalHigh");
        List<LabTestReferenceRangeDTO> ranges2 = List.of(range(3.5, 5.0, 2.0, 6.5), range(3.5, 5.0, 4.0, 6.5));
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(ranges2, "referenceRanges"))
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.criticalLow.insideRange")
            .hasFieldOrPropertyWithValue("field", "referenceRanges[1].criticalLow");
        List<LabTestReferenceRangeDTO> ranges5 = List.of(range(3.9, 6.1, null, 5.0));
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(ranges5))
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.criticalHigh.insideRange")
            .hasFieldOrPropertyWithValue("field", "[0].criticalHigh");
    }

    @Test
    void qaD896TheBeanConstraintNamesTheLimitItIsAbout() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(range(3.9, 6.1, null, 5.0)))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.getPropertyPath()).hasToString("criticalHigh");
                assertThat(v.getMessageTemplate()).isEqualTo("{lab.referenceRange.criticalHigh.insideRange}");
            });
        assertThat(validator.validate(range(3.9, 6.1, 4.5, 5.0)))
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactlyInAnyOrder("criticalLow", "criticalHigh");
        assertThat(validator.validate(range(3.9, 6.1, 9.0, 2.0)))
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("criticalHigh");
    }

    @Test
    void anOrderedRangeIsAccepted() {
        List<LabTestReferenceRangeDTO> ranges3 = List.of(range(3.5, 5.0, 2.5, 6.5));
        assertThatCode(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(ranges3))
            .doesNotThrowAnyException();
        List<LabTestReferenceRangeDTO> ranges4 = List.of(range(3.5, null, null, null));
        assertThatCode(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(ranges4))
            .doesNotThrowAnyException();
    }

    @Test
    void theBodyIsRefusedByBeanValidationToo() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(range(5.0, 3.5, null, null))).isNotEmpty();
        assertThat(validator.validate(range(3.5, 5.0, 2.5, 6.5))).isEmpty();
        assertThat(validator.validate(range(3.5, 5.0, 6.5, 2.5))).isNotEmpty();
    }
}
