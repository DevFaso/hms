package com.example.hms.service.pharmacy;

/**
 * How "the partner never delivered" is carried on a routing decision's
 * {@code reason} column, and how it is read back out.
 *
 * <p><b>Why this exists.</b> Recording a no-show used to compose the English
 * literal {@code "Partner no-show: "} in front of whatever the pharmacist
 * typed, and that sentence was then rendered verbatim to French- and
 * Spanish-speaking prescribers in the routing history. Stored text cannot be
 * translated at render time, so the fact and the free text are separated:
 * the column carries a machine-readable marker the client translates, and the
 * pharmacist's own words are kept exactly as typed.
 *
 * <p><b>Existing rows.</b> Everything written before this change still holds
 * the English literal. {@link #isNoShow} and {@link #freeText} recognise both
 * forms, so a legacy row decodes to the same flag and the same free text as a
 * new one and nothing has to be rewritten in the database.
 *
 * <p>The column also keeps the routing reason the decision was originally
 * taken for, joined by {@code " | "}. Only a segment that STARTS with the
 * marker or the legacy literal counts: the routing reason is free text a
 * pharmacist typed, and one that happens to mention a partner no-show must
 * not turn an ordinary decision into a flagged one.
 */
public final class PartnerNoShowReason {

    /** What a no-show segment starts with from now on. */
    static final String MARKER = "[PARTNER_NO_SHOW]";

    /** What a no-show segment started with before the marker existed. */
    static final String LEGACY_PREFIX = "Partner no-show:";

    private static final String SEPARATOR = " | ";

    /** The {@code reason} column is 1024 characters. */
    private static final int MAX_LENGTH = 1024;

    private PartnerNoShowReason() {
    }

    /**
     * Appends the no-show marker and the pharmacist's words to whatever the
     * decision was already recorded for.
     *
     * <p>When the two together overflow the column it is the EXISTING reason
     * that gives way, never the marker: truncating the tail would slice
     * through the marker itself, and the decision would then come back with
     * no no-show flag at all — the fact lost rather than the prose.
     *
     * @param existing     the routing reason the decision was taken for, may be null
     * @param noShowReason the pharmacist's own words, already trimmed and non-blank
     */
    public static String compose(String existing, String noShowReason) {
        String segment = MARKER + " " + noShowReason;
        if (segment.length() > MAX_LENGTH) {
            // Even alone it does not fit: keep the marker, shorten the words.
            return segment.substring(0, MAX_LENGTH);
        }
        if (existing == null || existing.isBlank()) {
            return segment;
        }
        int roomForExisting = MAX_LENGTH - segment.length() - SEPARATOR.length();
        String head = existing;
        if (roomForExisting <= 0) {
            return segment;
        }
        if (head.length() > roomForExisting) {
            head = head.substring(0, roomForExisting);
        }
        return head + SEPARATOR + segment;
    }

    /** True when this stored reason records a partner no-show, in either form. */
    public static boolean isNoShow(String stored) {
        return markerIndex(stored) >= 0;
    }

    /**
     * The stored reason with the no-show segment removed, or null when nothing
     * is left — what the client renders as the decision's reason, with the
     * no-show itself rendered from the flag in the reader's own language.
     */
    public static String withoutNoShow(String stored) {
        int idx = markerIndex(stored);
        if (idx < 0) {
            return stored;
        }
        String head = stored.substring(0, idx).trim();
        while (head.endsWith("|")) {
            head = head.substring(0, head.length() - 1).trim();
        }
        return head.isEmpty() ? null : head;
    }

    /**
     * The pharmacist's own words from the no-show segment, or null when the
     * reason records no no-show (or the segment carried no words at all).
     */
    public static String freeText(String stored) {
        int idx = markerIndex(stored);
        if (idx < 0) {
            return null;
        }
        String segment = stored.substring(idx);
        String prefix = segment.startsWith(MARKER) ? MARKER : LEGACY_PREFIX;
        String text = segment.substring(prefix.length()).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Where the no-show segment starts, or -1.
     *
     * <p>Only the start of a segment counts — the beginning of the column, or
     * just after a {@code " | "} separator. A pharmacist's own routing reason
     * may contain either phrase ("Partner no-show: last time, so routing
     * elsewhere"), and treating that as the fact would put a translated "the
     * partner never delivered" on a decision nobody recorded one for, and
     * strip their sentence out of the reason while doing it.
     *
     * <p>Scanned by index rather than split on the separator because the
     * pharmacist's own words may contain one too.
     */
    private static int markerIndex(String stored) {
        if (stored == null || stored.isBlank()) {
            return -1;
        }
        int marker = segmentStartIndexOf(stored, MARKER);
        return marker >= 0 ? marker : segmentStartIndexOf(stored, LEGACY_PREFIX);
    }

    /**
     * A client-authored routing reason with any no-show token neutralised.
     *
     * <p>The reason and the no-show marker share one column, and index 0 is
     * where an authored reason starts — the very position a real no-show
     * segment also occupies when the decision had no earlier reason. The two
     * cannot be told apart after the fact, so they are kept apart before it:
     * a reason arriving with either token has it bracketed as quoted text, and
     * the decision reads back as the ordinary route it is.
     */
    public static String defuseAuthoredReason(String authored) {
        if (authored == null || authored.isBlank()) {
            return authored;
        }
        String defused = authored.replace(MARKER, "\"" + MARKER + "\"")
                .replace(LEGACY_PREFIX, "\"" + LEGACY_PREFIX + "\"");
        // Two characters per occurrence, and the request is validated at the
        // column's own 1024 — so a full-length reason mentioning a no-show
        // would overflow the insert and answer the route with a 500.
        return defused.length() > MAX_LENGTH ? defused.substring(0, MAX_LENGTH) : defused;
    }

    /** The first index at which {@code token} begins a segment, or -1. */
    private static int segmentStartIndexOf(String stored, String token) {
        int from = 0;
        while (true) {
            int at = stored.indexOf(token, from);
            if (at < 0) {
                return -1;
            }
            if (at == 0 || stored.startsWith(SEPARATOR, at - SEPARATOR.length())) {
                return at;
            }
            from = at + token.length();
        }
    }
}
