package com.example.hms.enums;

/**
 * Go-live D2 (V385): what a version of a medication catalogue entry carries. The two are approved apart, by
 * different people: the formulary entry itself, and its dose limits.
 */
public enum MedicationCatalogVersionKind {
    /** The entry a prescriber picks and a pharmacist dispenses: names, ATC, form, strength, route, controlled. */
    FORMULARY,
    /** The dose limits the dose check reads: mg/kg per dose and per day, absolute maxima, weight bands. */
    DOSE_LIMIT
}
