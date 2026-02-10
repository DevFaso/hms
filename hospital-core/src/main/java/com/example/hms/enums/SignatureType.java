package com.example.hms.enums;

/**
 * Enumeration of different types of clinical reports that can be digitally signed.
 * Story #17: Generic Report Signing API
 */
public enum SignatureType {
    /**
     * Discharge summary document
     */
    DISCHARGE_SUMMARY,

    /**
     * Laboratory test result
     */
    LAB_RESULT,

    /**
     * Radiology/imaging report
     */
    IMAGING_REPORT,

    /**
     * Operative/surgical note
     */
    OPERATIVE_NOTE,

    /**
     * Consultation note from specialist
     */
    CONSULTATION_NOTE,

    /**
     * Progress note during patient care
     */
    PROGRESS_NOTE,

    /**
     * Procedure report
     */
    PROCEDURE_REPORT,

    /**
     * Pathology report
     */
    PATHOLOGY_REPORT,

    /**
     * Emergency department note
     */
    ED_NOTE,

    /**
     * Medication order/prescription
     */
    MEDICATION_ORDER,

    /**
     * Care plan document
     */
    CARE_PLAN,

    /**
     * Generic report/document type
     */
    GENERIC_REPORT
}
