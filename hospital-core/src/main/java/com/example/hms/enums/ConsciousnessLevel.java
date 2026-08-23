package com.example.hms.enums;

/**
 * ACVPU consciousness assessment (P3 #25b — NEWS2). Anything other than
 * ALERT scores 3 on the NEWS2 consciousness parameter; NEW_CONFUSION is
 * the "C" NEWS2 added over classic AVPU.
 */
public enum ConsciousnessLevel {
    ALERT,
    /** New-onset confusion — the C in ACVPU. */
    NEW_CONFUSION,
    VOICE,
    PAIN,
    UNRESPONSIVE
}
