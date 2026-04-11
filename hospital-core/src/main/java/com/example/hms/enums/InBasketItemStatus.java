package com.example.hms.enums;

/**
 * Lifecycle states for In-Basket items.
 */
public enum InBasketItemStatus {
    /** Not yet opened by recipient. */
    UNREAD,
    /** Opened/viewed but not formally acknowledged. */
    READ,
    /** Provider has formally acknowledged review of this item. */
    ACKNOWLEDGED
}
