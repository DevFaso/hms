package com.example.hms.enums;

/**
 * Lifecycle of one empanelment row (Tier 2 item 37). Two states on purpose:
 * a reassignment ENDs the old row and creates a new ACTIVE one — history
 * accumulates, nothing is overwritten.
 */
public enum PanelAssignmentStatus {
    ACTIVE,
    ENDED
}
