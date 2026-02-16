package com.example.hms.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Canonical sections that a doctor can update inside a patient chart entry.
 */
@Schema(description = "Standardized section identifiers stored alongside doctor chart updates.")
public enum DoctorChartSectionType {
    DIAGNOSIS,
    PROBLEM,
    ALLERGY,
    MEDICAL_HISTORY,
    SURGICAL_HISTORY,
    SOCIAL_FACTOR,
    SOCIAL_HISTORY,
    FAMILY_HISTORY,
    HOSPITALIZATION,
    IMMUNIZATION,
    CARE_PLAN,
    MEDICATION,
    NOTE,
    OTHER
}
