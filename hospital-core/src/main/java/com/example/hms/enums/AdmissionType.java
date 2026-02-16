package com.example.hms.enums;

/**
 * Admission type classification for hospital admissions.
 */
public enum AdmissionType {
    /**
     * Emergency admission through ED
     */
    EMERGENCY,
    
    /**
     * Scheduled/planned admission
     */
    ELECTIVE,
    
    /**
     * Urgent admission (not through ED)
     */
    URGENT,
    
    /**
     * Newborn admission (birth)
     */
    NEWBORN,
    
    /**
     * Transfer from another facility
     */
    TRANSFER,
    
    /**
     * Observation admission (< 24 hours)
     */
    OBSERVATION,
    
    /**
     * Day surgery/same-day admission
     */
    DAY_CASE,
    
    /**
     * Labor and delivery admission
     */
    LABOR_DELIVERY,
    
    /**
     * Psychiatric admission
     */
    PSYCHIATRIC
}
