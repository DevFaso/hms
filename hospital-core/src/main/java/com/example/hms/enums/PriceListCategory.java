package com.example.hms.enums;

/**
 * Billing-money plan PR 2 (V302): the category of a price-list item. It groups the hospital's tariff on the screen
 * and, from PR 6, lets a payer plan rule cover or exclude a whole category.
 */
public enum PriceListCategory {
    CONSULTATION,
    LAB_TEST,
    IMAGING,
    PROCEDURE,
    BED_DAY,
    TRANSFUSION,
    DELIVERY,
    OTHER_SERVICE
}
