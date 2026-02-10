package com.example.hms.enums;

/**
 * Type of referral based on care coordination needs
 */
public enum ReferralType {
    /**
     * Consultation - opinion requested, patient returns to referring provider
     */
    CONSULTATION,
    
    /**
     * Shared care - both providers manage patient collaboratively
     */
    SHARED_CARE,
    
    /**
     * Transfer of care - full handoff to receiving provider
     */
    TRANSFER_OF_CARE,
    
    /**
     * Emergency referral - urgent transfer needed
     */
    EMERGENCY_TRANSFER
}
