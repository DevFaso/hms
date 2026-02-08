package com.example.hms.enums;

/**
 * Represents the lifecycle status of an inpatient stay from admission to discharge.
 */
public enum PatientStayStatus {
    ADMITTED,
    READY_FOR_DISCHARGE,
    DISCHARGED,
    TRANSFERRED,
    HOLD
}
