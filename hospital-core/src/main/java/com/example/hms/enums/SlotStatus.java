package com.example.hms.enums;

/**
 * Lifecycle of a bookable appointment slot (P2 #11).
 *
 * <p>HELD exists because booking is not instantaneous: a patient choosing a
 * time, confirming details and paying takes minutes, and without a hold two
 * people can be offered the same moment and only one can have it. A derived
 * "template minus existing appointments" view cannot express this state, which
 * is the main reason slots are materialised rather than computed.
 */
public enum SlotStatus {

    /** Free and offerable. */
    OPEN,

    /** Reserved briefly while a booking completes; reclaimed when held_until passes. */
    HELD,

    /** Taken, with appointment_id pointing at the booking. */
    BOOKED,

    /** Deliberately unavailable — leave, a meeting, an equipment outage. */
    BLOCKED
}
