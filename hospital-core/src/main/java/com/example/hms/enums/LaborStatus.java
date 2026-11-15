package com.example.hms.enums;

public enum LaborStatus {
    ACTIVE,
    DELIVERED,
    /** Legacy value, never set by the service; kept so stored rows still read. */
    TRANSFERRED,
    CLOSED,
    /** Handed to another hospital by this hospital's clinician (provisional rule, 2026-11-12). */
    TRANSFERRED_OUT,
    /** She arrived at another hospital without a handover; that hospital recorded it and took over. */
    LEFT_WITHOUT_HANDOVER
}
