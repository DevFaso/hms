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
 * <p><b>Demographics and OBX-5: refused too, though not identifiers.</b>
 * PID-5 (the three name components), PID-8 and PID-11 (address line, city,
 * state, zip, country) are written by
 * {@code MllpInboundAdtServiceImpl.applyDemographics} into {@code Patient};
 * OBX-5 by {@code MllpInboundLabServiceImpl} and the HTTP ingest into
 * {@code lab_results.result_value}. Unbounded, an over-width value failed at
 * flush: {@code AE Server-side handler error}, no dead-letter row, a sender
 * retrying for ever, and on the ORU path a RECEIVED row for a message that
 * was rolled back. Refused, never truncated, for the same reason as the
 * identifiers: a cut name or a cut result value is a different value than the
 * one sent, stored as if it were the one sent. Each is checked by its service
 * before any lookup and answered AE with a dead-letter row naming the field and
 * the limit. PID-7 is parsed to a date and has no width. OBX-3, OBX-6, OBX-7
 * and OBX-11 are still truncated to their columns where they are written - an
 * older, different decision outside this class.
 *
 * <p>The {@code Patient} fields carry {@code @Size(max)} as well as a column
 * width, and bean validation runs at flush and counts UTF-16 units
 * ({@code String.length()}), so they are checked with {@link #fitsSize}, the
 * stricter count. {@code Patient.addressLine1} is an encrypted {@code TEXT}
 * column: its only limit is that {@code @Size(max = 255)}.
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

    /** PID-5 family, given and middle name: {@code Patient} first/last/middle name, 100. */
    public static final int PERSON_NAME_MAX = 100;

    /** PID-8: {@code Patient.gender}, 10. */
    public static final int SEX_MAX = 10;

    /**
     * PID-11's street line: {@code Patient.addressLine1}. The column is
     * encrypted {@code TEXT}, so this is its {@code @Size(max)}, not a column
     * width.
     */
    public static final int ADDRESS_LINE_MAX = 255;

    /** PID-11's city, state, zip and country: the {@code Patient} columns of 100. */
    public static final int ADDRESS_PART_MAX = 100;

    /** OBX-5: {@code lab_results.result_value}. */
    public static final int RESULT_VALUE_MAX = 2048;

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

    /**
     * Whether {@code value} passes a {@code @Size(max)} bean-validation check,
     * which counts UTF-16 units ({@code String.length()}) - stricter than
     * {@link #fits}, and the one that decides for a field that carries both a
     * {@code @Size} and a column width, because validation runs at flush
     * before the database sees the value. Absent is within bounds.
     */
    public static boolean fitsSize(String value, int max) {
        return value == null || value.length() <= max;
    }
}
