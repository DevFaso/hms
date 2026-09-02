package com.example.hms.enums;

/**
 * Where a programme enrolment stands (Tier 2 item 35).
 *
 * <p>ACTIVE is the only state the registry counts and the only one the
 * care-gap sweep (item 36) will trace. The closed states are deliberately
 * distinct rather than one CLOSED + reason text, because they answer
 * different reporting questions: LOST_TO_FOLLOW_UP is the defaulter-tracing
 * outcome national programmes ask facilities to count, TRANSFERRED_OUT keeps
 * the patient out of this facility's denominators without pretending the
 * care ended, and DECEASED must never receive an outreach message — a
 * distinction a free-text reason cannot enforce.
 */
public enum ProgramEnrollmentStatus {
    ACTIVE,
    /** The programme was completed — a cured TB course, a delivered ANC. */
    COMPLETED,
    /** Care continues, elsewhere. */
    TRANSFERRED_OUT,
    /** The tracing outcome, recorded after outreach failed — not a default. */
    LOST_TO_FOLLOW_UP,
    /** The patient chose to leave the programme. */
    WITHDRAWN,
    DECEASED
}
