package com.example.hms.enums;

/**
 * Insurer claims plan PR 3 (V458): what the hospital does with an amount a payer refused.
 * {@link #REBILL_PATIENT} moves it to the patient's share of the invoice; {@link #WRITE_OFF_REQUEST} asks another
 * person to approve giving it up; {@link #RESUBMIT} and {@link #CONTEST} leave it owed by the payer.
 */
public enum ClaimRefusalResolution {
    REBILL_PATIENT,
    WRITE_OFF_REQUEST,
    RESUBMIT,
    CONTEST
}
