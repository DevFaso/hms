package com.example.hms.enums;

/**
 * What kind of move a transfer order is (Tier 2 item 30).
 *
 * <p>Derived from the wards at order time and then STORED, so that renaming
 * or reorganising a ward later cannot silently rewrite what happened.
 */
public enum TransferType {

    /** Same ward, different bed — a bay move. */
    BED_TO_BED,

    /** A different ward, which is the move that changes who is responsible. */
    WARD_TO_WARD
}
