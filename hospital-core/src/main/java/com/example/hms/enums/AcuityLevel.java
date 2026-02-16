package com.example.hms.enums;

/**
 * Patient acuity level indicating severity of illness and required nursing resources.
 */
public enum AcuityLevel {
    /**
     * Minimal care needs
     */
    LEVEL_1_MINIMAL,
    
    /**
     * Stable with moderate care needs
     */
    LEVEL_2_MODERATE,
    
    /**
     * Requires frequent assessment and intervention
     */
    LEVEL_3_MAJOR,
    
    /**
     * Unstable, requires continuous monitoring
     */
    LEVEL_4_SEVERE,
    
    /**
     * Critical care, life-threatening
     */
    LEVEL_5_CRITICAL
}
