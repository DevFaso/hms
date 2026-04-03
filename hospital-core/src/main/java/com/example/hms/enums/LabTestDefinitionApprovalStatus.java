package com.example.hms.enums;

/**
 * Approval lifecycle for lab test definitions under CLIA/CAP/ISO 15189 regulations.
 *
 * Workflow:
 *   DRAFT → PENDING_QA_REVIEW → PENDING_DIRECTOR_APPROVAL → APPROVED → ACTIVE → RETIRED
 *                                                          ↘ REJECTED (returns to DRAFT)
 */
public enum LabTestDefinitionApprovalStatus {

    /** Initial state — being authored by Lab Scientist or Lab Manager. Not yet submitted. */
    DRAFT,

    /** Submitted for Quality Manager review and validation documentation check. */
    PENDING_QA_REVIEW,

    /** QA review passed — awaiting Lab Director final approval. */
    PENDING_DIRECTOR_APPROVAL,

    /** Approved by Lab Director — ready to be activated. */
    APPROVED,

    /** Activated and available for ordering. */
    ACTIVE,

    /** Rejected by Lab Director or Quality Manager — returned to DRAFT with a reason. */
    REJECTED,

    /** Retired / decommissioned — no longer available for new orders. */
    RETIRED
}
