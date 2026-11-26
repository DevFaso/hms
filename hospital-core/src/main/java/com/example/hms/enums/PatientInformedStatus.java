package com.example.hms.enums;

/** Whether the patient was told of the risk before an incompatible release (V381); an emergency may leave no time. */
public enum PatientInformedStatus {
    PATIENT_INFORMED,
    FAMILY_INFORMED,
    /** Unconscious, alone, or no time: told afterwards, and recorded then. */
    NOT_POSSIBLE
}
