package com.example.hms.enums;

/** Lifecycle of one unit being given at the bedside. */
public enum TransfusionAdministrationStatus {
    IN_PROGRESS,
    COMPLETED,
    /** Halted before the unit finished — most often a reaction. */
    STOPPED
}
