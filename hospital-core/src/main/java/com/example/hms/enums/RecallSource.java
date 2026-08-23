package com.example.hms.enums;

/** Where a recall row came from (P3 #22). */
public enum RecallSource {
    /** The checkout follow-up request — captured since MVP 6, dropped until V128. */
    CHECKOUT,
    MANUAL
}
