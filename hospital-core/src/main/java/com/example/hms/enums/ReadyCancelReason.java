package com.example.hms.enums;

/**
 * Why a prepared ({@link DispenseStatus#PENDING}) fill was cancelled (G15).
 *
 * <p>The first four are what a pharmacist may send to
 * {@code POST /pharmacy/dispense/{id}/cancel-ready}. The two
 * {@code PRESCRIPTION_*} values are written only by the system, when the
 * prescriber withdraws or edits the order, and are refused from the API.
 */
public enum ReadyCancelReason {
    STOCK_UNAVAILABLE,
    PATIENT_DECLINED,
    NOT_COLLECTED,
    OTHER,
    PRESCRIPTION_WITHDRAWN,
    PRESCRIPTION_CHANGED;

    /** True for the values a pharmacist may choose. */
    public boolean isPharmacistChoice() {
        return this != PRESCRIPTION_WITHDRAWN && this != PRESCRIPTION_CHANGED;
    }
}
