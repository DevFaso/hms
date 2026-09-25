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
 * taken for, joined by {@code " | "}; only the no-show segment is decoded.
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
     * @param existing    the routing reason the decision was taken for, may be null
     * @param noShowReason the pharmacist's own words, already trimmed and non-blank
     */
    public static String compose(String existing, String noShowReason) {
        String segment = MARKER + " " + noShowReason;
        String combined = existing == null || existing.isBlank() ? segment : existing + SEPARATOR + segment;
        return combined.length() > MAX_LENGTH ? combined.substring(0, MAX_LENGTH) : combined;
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
     * Where the no-show segment starts, or -1. The marker is always appended
     * last, so everything from it to the end of the column is the segment —
     * scanned by index rather than split on the separator, because the
     * pharmacist's own words may contain one.
     */
    private static int markerIndex(String stored) {
        if (stored == null || stored.isBlank()) {
            return -1;
        }
        int marker = stored.indexOf(MARKER);
        return marker >= 0 ? marker : stored.indexOf(LEGACY_PREFIX);
    }
}
