package com.example.hms.enums;

/**
 * How a bag left the lab on an emergency release (V266). User-supplied guidance, 2026-11-06.
 */
public enum EmergencyReleaseType {
    /** Group O red cells with no crossmatch, where no further step was required. */
    UNCROSSMATCHED,
    /** O positive red cells on a documented emergency decision: a reason and the person who made it. */
    O_POSITIVE_DECISION,
    /**
     * A red-cell bag issued despite an incompatible crossmatch, on a reason and a named authoriser.
     * The crossmatch result stays incompatible: this records a decision, it does not change the finding.
     */
    SEROLOGICALLY_INCOMPATIBLE
}
