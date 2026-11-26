package com.example.hms.enums;

/** Why O RhD negative did not go out instead, as recorded on every emergency O RhD positive release (V380). */
public enum ONegativeStatus {
    /** The system showed no O negative of this product in stock. */
    OUT_OF_STOCK,
    /** The system showed some, and the person releasing stated it cannot be obtained in time. */
    STATED_UNOBTAINABLE,
    /** The protocol gives O positive first to this patient group and keeps O negative for the others. */
    PROTOCOL_FIRST_LINE
}
