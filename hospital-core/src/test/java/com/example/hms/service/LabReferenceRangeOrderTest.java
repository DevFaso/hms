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
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(List.of(range(5.0, 3.5, null, null))))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.inverted");
    }

    @Test
    void invertedOrInsideCriticalLimitsAreRefused() {
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(List.of(range(3.5, 5.0, 6.5, 2.5))))
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.criticalInverted");
        assertThatThrownBy(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(List.of(range(3.5, 5.0, 4.0, 6.5))))
            .hasFieldOrPropertyWithValue("messageKey", "lab.referenceRange.criticalInverted");
    }

    @Test
    void anOrderedRangeIsAccepted() {
        assertThatCode(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(List.of(range(3.5, 5.0, 2.5, 6.5))))
            .doesNotThrowAnyException();
        assertThatCode(() -> LabTestDefinitionServiceImpl.requireOrderedRanges(List.of(range(3.5, null, null, null))))
            .doesNotThrowAnyException();
    }

    @Test
    void theBodyIsRefusedByBeanValidationToo() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(range(5.0, 3.5, null, null))).isNotEmpty();
        assertThat(validator.validate(range(3.5, 5.0, 2.5, 6.5))).isEmpty();
    }
}
