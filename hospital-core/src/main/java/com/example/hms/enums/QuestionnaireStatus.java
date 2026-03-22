package com.example.hms.enums;

/**
 * Lifecycle status for a patient's pre-visit questionnaire response.
 */
public enum QuestionnaireStatus {
    /** Response submitted by the patient — awaiting provider review. */
    SUBMITTED,
    /** Response has been reviewed by a provider. */
    REVIEWED
}
