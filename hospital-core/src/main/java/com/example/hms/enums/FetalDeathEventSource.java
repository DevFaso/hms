package com.example.hms.enums;

/** QA D1714: where a fetal-death event came from. */
public enum FetalDeathEventSource {
    /** Recorded with, or corrected on, the delivery record. */
    DELIVERY,
    /** Created once from a delivery recorded before V293 with {@code liveBirth = false}, from its recorded facts. */
    DELIVERY_HISTORICAL
}
