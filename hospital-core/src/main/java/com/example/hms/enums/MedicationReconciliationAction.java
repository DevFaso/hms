package com.example.hms.enums;

/**
 * Medication reconciliation actions from admission to discharge
 * Part of Story #14: Discharge Summary Assembly
 */
public enum MedicationReconciliationAction {
    /** Medication was on admission and continues at discharge unchanged */
    CONTINUED,
    
    /** Medication was on admission but discontinued during hospitalization */
    DISCONTINUED,
    
    /** New medication started during hospitalization, continues at discharge */
    NEW_STARTED,
    
    /** Medication dose or frequency changed */
    MODIFIED,
    
    /** Medication was on admission, held during hospitalization, resume at discharge */
    RESUMED,
    
    /** Medication was on admission, replaced with different medication */
    REPLACED,
    
    /** Temporary medication for hospitalization only, not continuing */
    TEMPORARY_ONLY
}
