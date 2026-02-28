package com.example.hms.enums;

/**
 * Represents the lifecycle status of an inpatient stay from admission to discharge.
 */
public enum PatientStayStatus {
    REGISTERED,         // Legacy: patient registered but not yet formally admitted
    ADMITTED,
    READY_FOR_DISCHARGE,
    DISCHARGED,
    TRANSFERRED,
    HOLD
}
