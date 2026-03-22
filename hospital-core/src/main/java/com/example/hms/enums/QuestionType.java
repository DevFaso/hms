package com.example.hms.enums;

/**
 * Defines the type of question in a pre-visit questionnaire.
 */
public enum QuestionType {
    /** Free-form text input. */
    TEXT,
    /** A simple Yes/No toggle. */
    YES_NO,
    /** A numeric scale, typically 1-10. */
    SCALE,
    /** A single choice from a list of options. */
    CHOICE
}
