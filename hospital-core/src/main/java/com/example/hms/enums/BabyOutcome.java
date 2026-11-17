package com.example.hms.enums;

/**
 * QA D1714 (user decision 2026-11-13): the outcome of one baby of a delivery. A live baby is registered as a patient
 * (QA D1592); a stillborn baby is not, and creates a fetal-death event instead.
 */
public enum BabyOutcome {
    LIVE,
    STILLBORN
}
