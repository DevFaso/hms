package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.SensitivityCategory;

import java.util.UUID;

/**
 * Both halves are returned because they differ and the difference matters:
 * {@code explicitCategory} is what is stored on this row, {@code effectiveCategory}
 * is what actually governs, after the department default is applied.
 *
 * @param travelsCrossHospital whether a row of this effective category may be
 *                             read from another hospital on the treatment
 *                             presumption — false for every named category
 */
public record SensitivityTagResponseDTO(
    UUID id,
    SensitivityCategory explicitCategory,
    SensitivityCategory effectiveCategory,
    SensitivityCategory departmentDefault,
    boolean travelsCrossHospital
) {
}
