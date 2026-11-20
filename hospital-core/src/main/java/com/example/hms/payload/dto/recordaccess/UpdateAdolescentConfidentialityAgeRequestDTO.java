package com.example.hms.payload.dto.recordaccess;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body for setting a hospital's adolescent confidentiality age. {@code null}
 * is meaningful: it returns the hospital to the provisional default.
 */
public record UpdateAdolescentConfidentialityAgeRequestDTO(
    @Min(0) @Max(18) Integer age
) {
}
