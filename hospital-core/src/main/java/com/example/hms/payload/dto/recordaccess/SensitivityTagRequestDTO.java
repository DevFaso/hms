package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.SensitivityCategory;

/**
 * Body for setting a sensitivity tag. {@code null} is meaningful and allowed:
 * it clears the explicit tag, after which the row falls back to its
 * department's default (or to untagged, where there is no department).
 */
public record SensitivityTagRequestDTO(
    SensitivityCategory category
) {
}
