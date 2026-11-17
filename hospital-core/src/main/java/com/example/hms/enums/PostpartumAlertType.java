package com.example.hms.enums;

/**
 * Categorizes clinical alerts generated from postpartum observation data.
 */
public enum PostpartumAlertType {
    HEMORRHAGE,
    INFECTION,
    /** QA D1890 (provisional): severe blood pressure after birth. */
    HYPERTENSION,
    PAIN,
    PSYCHOSOCIAL,
    EDUCATION,
    FOLLOW_UP
}
