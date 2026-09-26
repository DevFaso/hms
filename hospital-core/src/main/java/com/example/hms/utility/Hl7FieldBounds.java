package com.example.hms.utility;

/**
 * The widths inbound HL7 v2 identifiers are held to, checked once where each
 * is read, so no sink downstream has to make one of them safe.
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
 * the first component of PV1-3 a department. A truncated value can collide
 * with another one - two control ids sharing a prefix would read as a replay
 * of each other - so cutting one short changes what a legitimate message
 * means. Each limit is the width of the column the field is matched against
 * or written to, so a wider value could never have matched or been stored
 * (padding aside - see below); refusing it turns a flush error or a silent
 * non-match into an explicit rejection.
 *
 * <p><b>Where each is checked.</b> MSH-3, MSH-4 and MSH-10 in
 * {@code Hl7MessageInspector.parseHeader}, as an invalid MSH, for every
 * transport. PID-3 and MRG-1 in the ADT and A40 parsers of
 * {@link Hl7v2MessageBuilder}, which only MLLP uses. OBR-2 in
 * {@code MllpInboundLabServiceImpl}, because the ORU parser is shared with
 * paths where OBR-2 is not an accession and any width works. PV1-19 and
 * PV1-3 in {@code MllpInboundAdtVisitProjectionServiceImpl}, their only
 * reader, which skips the projection and keeps the demographic update.
 *
 * <p><b>Not covered: demographics and OBX-5.</b> PID-5, PID-7, PID-8 and
 * PID-11 are written by {@code MllpInboundAdtServiceImpl.applyDemographics}
 * into {@code Patient} columns of 100 characters (sex: 10); OBX-5 is written
 * by {@code MllpInboundLabServiceImpl} into {@code lab_results.result_value}
 * (2048). None is bounded here. They are not identifiers, so refuse versus
 * truncate is a separate decision; until it is made, an over-width value
 * fails at flush, the sender gets {@code AE Server-side handler error} with
 * no dead-letter row, and it retries indefinitely. OBX-3, OBX-6, OBX-7 and
 * OBX-11 are truncated to their columns where they are written - an older,
 * different decision, also outside this class. Known debt, not an oversight.
 *
 * <p>The limits are column widths, not HL7's nominal field lengths: real
 * senders exceed v2.5's 20-character MSH-10, and a tighter bound would refuse
 * messages that store and match correctly. Each is a copy of an entity's
 * {@code @Column(length)}, and {@code Hl7FieldBoundsColumnWidthTest} fails
 * the build when a migration moves one without the other. Widths are counted
 * in characters (code points), as {@code VARCHAR(n)} counts them.
 *
 * <p><b>MSH-9 is not bounded.</b> It is a routing code, not an identifier;
 * the HTTP ORU ingest never reads it; and the one column it reaches,
 * {@code integration_message_event.message_type}, is already clamped by
 * {@code IntegrationMessageRecorder}. Bounding it in the shared inspector
 * refused messages over a field nothing on that path reads.
 *
 * <p><b>Whitespace.</b> The MSH fields are bounded untrimmed, because they
 * reach sinks untrimmed - MSA-2 echoes MSH-10 as sent, and the dispatcher
 * logs the sender pair as sent - so bounding them trimmed would let a sender
 * pad them without limit. The cost: an MSH field that fits only once its
 * padding is stripped is refused, although the allowlist match (which trims)
 * would have found it. PID-3, MRG-1, OBR-2, PV1-19 and PV1-3 are bounded
 * trimmed, exactly as they are matched, because every reader trims them
 * first.
 */
public final class Hl7FieldBounds {

    /** MSH-3 and MSH-4: {@code mllp_allowed_senders.sending_application/_facility}. */
    public static final int SENDER_FIELD_MAX = 180;

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

    /**
     * Whether {@code value} fits a {@code VARCHAR(max)} column. Absent is
     * within bounds: whether a field is mandatory is decided elsewhere.
     *
     * <p>Counted in code points, as Postgres counts {@code VARCHAR(n)}, not
     * in UTF-16 units: {@code String.length()} counts a supplementary-plane
     * character (an emoji, a rare CJK ideograph) twice, and would refuse a
     * name that fits its column.
     */
    public static boolean fits(String value, int max) {
        return value == null || value.codePointCount(0, value.length()) <= max;
    }
}
