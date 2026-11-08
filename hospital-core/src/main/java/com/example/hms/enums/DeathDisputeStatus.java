package com.example.hms.enums;

/** Where a dispute of a recorded death stands. Only an administrator's decision ends one, never a timeout. */
public enum DeathDisputeStatus {
    /** Care is permitted at the disputing hospital, pending review. */
    OPEN,
    /** An administrator upheld the death: the block is back. */
    UPHELD,
    /** The death record was marked entered in error, which ends every dispute of it. */
    ENTERED_IN_ERROR
}
