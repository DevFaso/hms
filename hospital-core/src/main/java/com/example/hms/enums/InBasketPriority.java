package com.example.hms.enums;

/**
 * Priority levels for In-Basket items.
 */
public enum InBasketPriority {
    /** Standard clinical notification. */
    NORMAL,
    /** Requires prompt attention. */
    URGENT,
    /** Life-threatening / critical value — requires immediate action. */
    CRITICAL
}
