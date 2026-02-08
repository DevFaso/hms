package com.example.hms.enums;

/**
 * Categories for findings detected during ultrasound examination.
 */
public enum UltrasoundFindingCategory {
    /**
     * All measurements and findings are within normal limits.
     */
    NORMAL,

    /**
     * Minor variations noted but not clinically significant.
     */
    VARIANT,

    /**
     * Findings require monitoring but no immediate intervention.
     */
    MONITORING_REQUIRED,

    /**
     * Abnormal findings requiring specialist consultation or follow-up.
     */
    ABNORMAL,

    /**
     * Findings suggest potential chromosomal abnormalities or genetic conditions.
     */
    CONCERNING_FOR_ANOMALY,

    /**
     * Urgent findings requiring immediate action or intervention.
     */
    URGENT
}
