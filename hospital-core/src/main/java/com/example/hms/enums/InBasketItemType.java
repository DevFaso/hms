package com.example.hms.enums;

/**
 * Categories of In-Basket items for clinical provider notifications.
 */
public enum InBasketItemType {
    /** Lab or imaging result requiring provider review. */
    RESULT,
    /** Order-related notification (e.g. order requires co-sign). */
    ORDER,
    /** Clinical message from staff or system. */
    MESSAGE,
    /** Task assignment (e.g. follow-up reminder). */
    TASK
}
