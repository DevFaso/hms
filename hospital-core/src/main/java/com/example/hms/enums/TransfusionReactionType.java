package com.example.hms.enums;

/** Recognised transfusion reaction categories (ISBT/AABB vocabulary). */
public enum TransfusionReactionType {
    FEBRILE_NON_HEMOLYTIC,
    ACUTE_HEMOLYTIC,
    DELAYED_HEMOLYTIC,
    ALLERGIC,
    ANAPHYLACTIC,
    /** Transfusion-associated circulatory overload. */
    TACO,
    /** Transfusion-related acute lung injury. */
    TRALI,
    SEPTIC,
    HYPOTENSIVE,
    OTHER
}
