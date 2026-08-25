package com.example.hms.enums;

/**
 * Outcome of the antibody screen half of a type and screen.
 *
 * <p>NOT_DONE is a real state, not a placeholder: an ABO/Rh group can be on
 * record from a previous admission while the screen has never been run or has
 * expired. The crossmatch path refuses to proceed on NOT_DONE rather than
 * treating a missing screen as a negative one.
 */
public enum AntibodyScreenResult {
    NEGATIVE,
    POSITIVE,
    NOT_DONE
}
