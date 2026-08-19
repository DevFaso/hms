package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.CdsAlertSeverity;

import java.util.List;

/**
 * P-08: Result of a prospective CDS check at dispense time.
 *
 * @param severity         highest severity found across all checks
 * @param alerts           human-readable alert messages (FR-friendly)
 * @param requiresOverride true when severity is {@link CdsAlertSeverity#CRITICAL}
 *                         — the caller must collect an override reason from the
 *                         pharmacist before proceeding with the dispense.
 */
public record CdsAlertResult(
        CdsAlertSeverity severity,
        List<String> alerts,
        boolean requiresOverride
) {
    public static CdsAlertResult clear() {
        return new CdsAlertResult(CdsAlertSeverity.NONE, List.of(), false);
    }
}
