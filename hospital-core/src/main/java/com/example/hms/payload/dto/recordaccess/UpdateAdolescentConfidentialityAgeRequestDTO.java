package com.example.hms.payload.dto.recordaccess;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body for setting a hospital's adolescent confidentiality age. {@code null}
 * is meaningful: it returns the hospital to the provisional default.
 */
public record UpdateAdolescentConfidentialityAgeRequestDTO(
    // r66 D2904: the range in one sentence, not « âge : doit être inférieur ou égal à 18 ».
    @Min(value = 0, message = "{adolescentAge.range}")
    @Max(value = 18, message = "{adolescentAge.range}") Integer age
) {
}
