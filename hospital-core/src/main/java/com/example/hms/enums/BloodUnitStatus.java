package com.example.hms.enums;

/** Lifecycle of a physical blood unit inside the facility. */
public enum BloodUnitStatus {
    /** On hand, not committed to any patient. */
    AVAILABLE,
    /** Crossmatched against a request and reserved for that patient. */
    CROSSMATCHED,
    /** Released from the lab to the ward for this patient. */
    ISSUED,
    /** Hung and given. Terminal. */
    TRANSFUSED,
    /** Came back unused and is fit to re-enter stock. */
    RETURNED,
    /** Destroyed — breach of cold chain, reaction workup, or damage. Terminal. */
    DISCARDED,
    /** Past its expiry date. Terminal. */
    EXPIRED
}
