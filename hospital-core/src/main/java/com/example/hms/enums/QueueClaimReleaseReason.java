package com.example.hms.enums;

/**
 * G13: why a work-queue claim ended. {@link #RELEASED} is the holder's own
 * release; every other value names the write that ended the work on the
 * order (plan rule 4). Written into the audit description as a code.
 */
public enum QueueClaimReleaseReason {
    RELEASED,
    DISPENSED,
    PREPARED,
    ROUTED,
    BACK_ORDERED,
    CLARIFICATION_REQUESTED,
    DISPATCHED,
    WITHDRAWN,
    CHANGED;

    /**
     * A prescriber's withdrawal or edit ends the work, it does not take it
     * over: audited as RELEASED whoever the actor is.
     */
    public boolean alwaysRelease() {
        return this == WITHDRAWN || this == CHANGED;
    }
}
