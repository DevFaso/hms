package com.example.hms.enums;

/**
 * The two levels of life-threatening urgency a prescriber declares on a blood request (French practice, cited in
 * the rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN). The third level, « urgence relative » (2 to 3 hours, full
 * testing), is an ordinary URGENT request and never opens the emergency path.
 * Stored by name in {@code clinical.transfusion_requests.vital_urgency_level} (V362): never rename a constant.
 */
public enum VitalUrgencyLevel {
    /** Urgence vitale immédiate: group O units without delay. */
    UVI,
    /** Urgence vitale: issue within 30 minutes. */
    UV
}
