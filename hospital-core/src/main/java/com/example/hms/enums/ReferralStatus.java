package com.example.hms.enums;

/**
 * Status of a referral throughout its lifecycle
 */
public enum ReferralStatus {
    /**
     * Referral created but not yet sent
     */
    DRAFT,
    
    /**
     * Referral submitted to receiving provider
     */
    SUBMITTED,
    
    /**
     * Receiving provider acknowledged referral
     */
    ACKNOWLEDGED,
    
    /**
     * Patient appointment scheduled
     */
    SCHEDULED,
    
    /**
     * Consultation/care in progress
     */
    IN_PROGRESS,
    
    /**
     * Referral completed successfully
     */
    COMPLETED,
    
    /**
     * Referral cancelled by referring provider
     */
    CANCELLED,
    
    /**
     * Referral rejected/declined by receiving provider
     */
    REJECTED,
    
    /**
     * Referral expired (not acted upon in time)
     */
    EXPIRED
}
