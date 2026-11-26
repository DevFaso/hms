package com.example.hms.enums;

/** What the monitoring after an incompatible release found (V381). */
public enum IncompatibleMonitoringOutcome {
    NO_REACTION,
    /** A reaction was seen: it is recorded on the transfusion and declared to haemovigilance. */
    REACTION_REPORTED,
    /** The bag came back or was discarded without being transfused: nothing to monitor. */
    NOT_TRANSFUSED
}
