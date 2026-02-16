package com.example.hms.enums;

/**
 * Status of a hospital admission throughout its lifecycle.
 */
public enum AdmissionStatus {
    /**
     * Admission pending (pre-registration)
     */
    PENDING,
    
    /**
     * Patient admitted and active
     */
    ACTIVE,
    
    /**
     * On leave (temporary absence)
     */
    ON_LEAVE,
    
    /**
     * Awaiting discharge
     */
    AWAITING_DISCHARGE,
    
    /**
     * Discharged - admission complete
     */
    DISCHARGED,
    
    /**
     * Cancelled (admission did not occur)
     */
    CANCELLED,
    
    /**
     * Transferred to another facility/ward
     */
    TRANSFERRED,
    
    /**
     * Patient deceased
     */
    DECEASED
}
