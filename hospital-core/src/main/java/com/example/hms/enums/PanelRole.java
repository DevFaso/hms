package com.example.hms.enums;

/**
 * What kind of panel owner an empanelment names (Tier 2 item 37). A patient
 * can have one ACTIVE owner of each role per hospital — a primary provider
 * AND a community health worker commonly coexist.
 */
public enum PanelRole {
    PRIMARY_PROVIDER,
    /** Community health worker — the outreach half of panel management here. */
    CHW
}
