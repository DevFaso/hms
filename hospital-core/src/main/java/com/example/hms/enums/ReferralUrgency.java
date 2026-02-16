package com.example.hms.enums;

/**
 * Urgency level of referral
 */
public enum ReferralUrgency {
    /**
     * Routine - scheduled within standard timeframe (2-4 weeks)
     */
    ROUTINE,
    
    /**
     * Priority - needs faster scheduling (within 1 week)
     */
    PRIORITY,
    
    /**
     * Urgent - needs immediate attention (within 24-48 hours)
     */
    URGENT,
    
    /**
     * Emergency - life-threatening, immediate transfer needed
     */
    EMERGENCY
}
