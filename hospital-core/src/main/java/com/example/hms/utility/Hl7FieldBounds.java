package com.example.hms.utility;

/**
 * The widths inbound HL7 v2 fields are held to, checked once where each is
 * first read, so no sink downstream ever has to make a sender-controlled
 * value safe.
 *
 * <p><b>Why once, and not at each sink.</b> These fields come off the wire
 * with no length limit and reach log lines, audit descriptions, dead-letter
 * rows, idempotency keys and fixed-width columns in dozens of places.
 * Bounding them per call site was tried and did not converge: each review
 * found a sink the wrappers had not reached, and a setter write to a
 * {@code VARCHAR(255)} column is a sink no wrapper and no source scan sees.
 * Held to width at the first read, every later use is safe by construction.
 *
 * <p><b>Over-width is refused, never truncated.</b> Each of these fields is
 * matched or keyed on: MSH-10 is the idempotency key, MSH-3/MSH-4 match the
 * allowlist, OBR-2 an accession, PID-3/MRG-1 an EMPI alias, PV1-19 a visit,
 * the first component of PV1-3 a department. A truncated value can collide with another one - two
 * control ids sharing a prefix would read as a replay of each other - so
 * cutting one short changes what a legitimate message means. Each limit is
 * the width of the column the field is matched against or written to, so a
 * wider value could never have matched or been stored; refusing it turns a
 * flush error or a silent non-match into an explicit rejection.
 *
 * <p><b>Where each is checked.</b> The MSH fields in
 * {@code Hl7MessageInspector.parseHeader}, as an invalid MSH, for every
 * transport. PID-3, MRG-1, PV1-3 and PV1-19 in the ADT and A40 parsers of
 * {@link Hl7v2MessageBuilder}, which only MLLP uses. OBR-2 in
 * {@code MllpInboundLabServiceImpl}, because the ORU parser is shared with
 * paths where OBR-2 is not an accession and any width works.
 *
 * <p>The limits are column widths, not HL7's nominal field lengths: real
 * senders exceed v2.5's 20-character MSH-10, and a tighter bound would refuse
 * messages that store and match correctly. Whitespace counts, except for
 * OBR-2, which is matched trimmed.
 */
public final class Hl7FieldBounds {

    /** MSH-3 and MSH-4: {@code mllp_allowed_senders.sending_application/_facility}. */
    public static final int SENDER_FIELD_MAX = 180;

    /** MSH-9: {@code integration_message_event.message_type}. */
    public static final int MESSAGE_TYPE_MAX = 64;

    /**
     * MSH-10: {@code lab_results.source_message_control_id} and the
     * {@code external_message_control_id} columns on admissions and
     * encounters. It is the idempotency key, which is why it must never be
     * cut short.
     */
    public static final int MESSAGE_CONTROL_ID_MAX = 255;

    /** OBR-2, trimmed: {@code lab_specimens.accession_number}. */
    public static final int PLACER_ORDER_NUMBER_MAX = 50;

    /** PID-3 and MRG-1: {@code empi.identity_aliases.alias_value}. */
    public static final int MRN_MAX = 255;

    /** PV1-19: {@code external_visit_number} on admissions and encounters. */
    public static final int VISIT_NUMBER_MAX = 255;

    /**
     * PV1-3's first component, the point of care: the only part read. It is
     * matched against a department code or name and quoted into the A02
     * transfer audit description; the rest of the field is not bounded.
     */
    public static final int ASSIGNED_LOCATION_MAX = 255;

    private Hl7FieldBounds() {}

    /** Absent is within bounds: whether a field is mandatory is decided elsewhere. */
    public static boolean fits(String value, int max) {
        return value == null || value.length() <= max;
    }
}
