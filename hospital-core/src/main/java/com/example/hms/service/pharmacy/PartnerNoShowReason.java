package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;

/**
 * How "the partner never delivered" is carried on a routing decision's
 * {@code reason} column, and how it is read back out.
 *
 * <p><b>Why this exists.</b> Recording a no-show used to compose the English
 * literal {@code "Partner no-show: "} in front of whatever the pharmacist
 * typed, and that sentence was then rendered verbatim to French- and
 * Spanish-speaking prescribers in the routing history. Stored text cannot be
 * translated at render time, so the fact and the free text are separated: the
 * column carries a machine-readable marker the client translates, and the
 * pharmacist's own words are kept exactly as typed.
 *
 * <p><b>Only the marker counts, and only the server writes one.</b> An earlier
 * cut also decoded the old English phrase, so that existing rows would render
 * translated too. That cannot be done safely: the column is free text a
 * pharmacist types into, the phrase is ordinary prose ("Partner no-show: last
 * time, so routing elsewhere" is a perfectly good routing reason), and no
 * amount of anchoring tells the two apart once both are in the same column.
 * Three separate defects came out of trying — a PENDING decision labelled as a
 * no-show, a superseded one labelled likewise, and a prescriber shown a
 * sentence with its opening words cut off. So the phrase is no longer read as
 * anything: it is prose, and it is displayed as typed.
 *
 * <p><b>What that means for rows already in the table.</b> They keep their
 * English literal and render exactly as they do today — no better, no worse,
 * and no regression. Converting them is a data migration, which needs a
 * number; see the PR body.
 *
 * <p>The column also keeps the routing reason the decision was originally
 * taken for, joined by {@code " | "}. Only a segment that STARTS with the
 * marker counts, so a marker quoted inside somebody's sentence is inert.
 */
public final class PartnerNoShowReason {

    /** What a no-show segment starts with. Written by this class only. */
    static final String MARKER = "[PARTNER_NO_SHOW]";

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
     * through the marker itself and the decision would come back with no
     * no-show flag at all — the fact lost rather than the prose. If the words
     * alone will not fit, the write is refused rather than shortened: eating
     * the end of a sentence somebody typed, with nothing on screen to say so,
     * is not something a pharmacy record should do quietly.
     *
     * @param existing     the routing reason the decision was taken for, may be null
     * @param noShowReason the pharmacist's own words, already trimmed and non-blank
     */
    public static String compose(String existing, String noShowReason) {
        String segment = MARKER + " " + noShowReason;
        if (segment.length() > MAX_LENGTH) {
            throw new BusinessException(
                    "This no-show reason is too long to record. Shorten it by at least "
                            + (segment.length() - MAX_LENGTH) + " characters.");
        }
        if (existing == null || existing.isBlank()) {
            return segment;
        }
        int roomForExisting = MAX_LENGTH - segment.length() - SEPARATOR.length();
        if (roomForExisting <= 0) {
            return segment;
        }
        String head = existing.length() > roomForExisting
                ? existing.substring(0, roomForExisting)
                : existing;
        return head + SEPARATOR + segment;
    }

    /** True when this stored reason records a partner no-show. */
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
        // The head is the routing reason, and it can carry a marker somebody
        // typed and this class quoted. It goes through the same cleaning the
        // non-no-show path gets: on a genuine no-show row the prescriber was
        // otherwise shown a raw [PARTNER_NO_SHOW] token in the reason field.
        return head.isEmpty() ? null : forDisplay(head);
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
        String text = stored.substring(idx + MARKER.length()).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * A client-authored routing reason with any marker neutralised.
     *
     * <p>The reason and the marker share one column, and index 0 — where an
     * authored reason starts — is also where a real no-show segment sits when
     * the decision had no earlier reason. So a reason arriving with the marker
     * in it has it quoted, and only the server can assert the fact.
     */
    public static String defuseAuthoredReason(String authored) {
        if (authored == null || authored.isBlank() || !authored.contains(MARKER)) {
            return authored;
        }
        String defused = authored.replace(MARKER, "\"" + MARKER + "\"");
        if (defused.length() > MAX_LENGTH) {
            throw new BusinessException(
                    "This routing reason is too long once the reserved marker in it is quoted. "
                            + "Shorten it by at least " + (defused.length() - MAX_LENGTH)
                            + " characters.");
        }
        return defused;
    }

    /**
     * What a stored reason should look like on screen when it is NOT a no-show.
     *
     * <p>Only the marker is removed, and only where this class put one: a
     * quoted marker loses its quotes and then, being ours rather than prose,
     * the marker itself. Nothing else is touched — the words a pharmacist
     * typed reach the reader as they typed them.
     */
    public static String forDisplay(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        String shown = stored.replace("\"" + MARKER + "\"", MARKER);
        int at = segmentStartIndexOf(shown, MARKER);
        if (at >= 0) {
            shown = (shown.substring(0, at) + shown.substring(at + MARKER.length())).trim();
        }
        shown = shown.trim();
        while (shown.startsWith("|")) {
            shown = shown.substring(1).trim();
        }
        return shown.isEmpty() ? null : shown;
    }

    /**
     * Where the no-show segment starts, or -1.
     *
     * <p>Only the start of a segment counts — the beginning of the column, or
     * just after a {@code " | "} separator — so a marker somebody quoted mid
     * sentence is inert. Scanned by index rather than split on the separator
     * because the pharmacist's own words may contain one too.
     */
    private static int markerIndex(String stored) {
        if (stored == null || stored.isBlank()) {
            return -1;
        }
        return segmentStartIndexOf(stored, MARKER);
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
