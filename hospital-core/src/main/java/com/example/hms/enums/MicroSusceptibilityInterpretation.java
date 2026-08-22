package com.example.hms.enums;

/**
 * Standard S/I/R interpretive category for one isolate x antibiotic pair
 * (P3 #19). An antibiotic that was not tested simply has no row.
 */
public enum MicroSusceptibilityInterpretation {
    SUSCEPTIBLE,
    INTERMEDIATE,
    RESISTANT
}
