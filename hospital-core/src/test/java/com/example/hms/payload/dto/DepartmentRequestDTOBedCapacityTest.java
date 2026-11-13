package com.example.hms.payload.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** D1790: a bed capacity below 0 is refused on the bedCapacity field. */
class DepartmentRequestDTOBedCapacityTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private DepartmentRequestDTO dto(Integer beds) {
        return DepartmentRequestDTO.builder()
            .name("Cardiologie")
            .code("CARD")
            .bedCapacity(beds)
            .build();
    }

    @Test
    void negativeCapacity_isRefusedOnBedCapacity() {
        assertThat(validator.validate(dto(-1)))
            .extracting(v -> v.getPropertyPath().toString())
            .contains("bedCapacity");
    }

    @Test
    void zeroAbsentAndPositiveCapacity_areAccepted() {
        for (Integer beds : new Integer[] {0, 12, null}) {
            assertThat(validator.validate(dto(beds)))
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("bedCapacity");
        }
    }
}
