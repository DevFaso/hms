package com.example.hms.enums;

public enum PatientDocumentType {
    LAB_RESULT,
    IMAGING_REPORT,
    DISCHARGE_SUMMARY,
    REFERRAL_LETTER,
    PRESCRIPTION,
    INSURANCE_DOCUMENT,
    INVOICE,
    IMMUNIZATION_RECORD,
    /** QA D961: a medical certificate or sick-leave note issued by a doctor from a visit. */
    MEDICAL_CERTIFICATE,
    OTHER
}
