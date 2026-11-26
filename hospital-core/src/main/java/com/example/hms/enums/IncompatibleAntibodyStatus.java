package com.example.hms.enums;

/** What the record of the patient said about antibodies when a bag went out despite an incompatible crossmatch (V381). */
public enum IncompatibleAntibodyStatus {
    /** A positive antibody screen or an identified antibody is on record, at this hospital or another. */
    ANTIBODY_ON_RECORD,
    /** Nothing on record: the incompatible crossmatch is the first sign. */
    NO_ANTIBODY_ON_RECORD
}
