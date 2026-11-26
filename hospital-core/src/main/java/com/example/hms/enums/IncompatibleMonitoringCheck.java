package com.example.hms.enums;

/**
 * The checklist of the rule after an incompatible release (V381, report « Incompatible blood release authority »,
 * from NHSBT INF437/5, SHOT 2017 and ARS Centre-Val de Loire 2026). Each is ticked when done; none is required to
 * close the monitoring: a small laboratory does not run every test.
 */
public enum IncompatibleMonitoringCheck {
    BEDSIDE_ABO_CHECK,
    /** 20 ml, then 50 ml, then the rest, watching pulse, blood pressure and temperature. */
    SLOW_START,
    /** The tubing of the unit is kept, so the crossmatch can be repeated. */
    TUBING_KEPT,
    URINE_DIPSTICK,
    FULL_BLOOD_COUNT,
    RETICULOCYTES,
    BILIRUBIN,
    LDH,
    HAPTOGLOBIN,
    CREATININE,
    COAGULATION
}
