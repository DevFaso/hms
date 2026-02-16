package com.example.hms.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the lifecycle status of a treatment plan.
 */
@Getter
@RequiredArgsConstructor
public enum TreatmentPlanStatus {
    DRAFT("draft"),
    IN_REVIEW("in_review"),
    REVISIONS_REQUIRED("revisions_required"),
    APPROVED("approved"),
    ARCHIVED("archived"),
    CANCELLED("cancelled");

    private final String label;
}
