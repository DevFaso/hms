package com.example.hms.enums;

/**
 * The status of a recorded immunization (QA D1348, round 30).
 *
 * <p>The vocabulary the series, overdue, reminder and DHIS2 export queries key
 * on ({@code status = 'COMPLETED'}), the void filter ({@code ENTERED_IN_ERROR})
 * and the FHIR mapper read. The request accepted any free text ("FAIT",
 * "Donné"), stored it raw, and such a dose was invisible to every one of those
 * readers. A request now names one of these values or is refused with
 * {@code fieldErrors.status}. The column stays a string, so a row stored before
 * this check still reads as it was.
 */
public enum ImmunizationStatus {
    COMPLETED,
    NOT_DONE,
    REFUSED,
    DEFERRED,
    ENTERED_IN_ERROR
}
