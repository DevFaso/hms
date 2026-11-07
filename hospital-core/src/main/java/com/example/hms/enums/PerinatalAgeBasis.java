package com.example.hms.enums;

/** What the perinatal classification's age at death rests on. It says how far that age can be trusted. */
public enum PerinatalAgeBasis {
    /** A birth time and a death time: completed days are known exactly. */
    ELAPSED_TIME,
    /** Only a date of birth, and the dates settle the classification either way. */
    DATE_ONLY,
    /** Only a date of birth, and the classification sits where the hour of birth would decide it. */
    DATE_ONLY_BOUNDARY,
    /** No date of birth, or a row from before this was assessed. Nothing is confirmed. */
    UNKNOWN
}
