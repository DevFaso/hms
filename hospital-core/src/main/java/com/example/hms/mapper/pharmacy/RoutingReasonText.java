package com.example.hms.mapper.pharmacy;

import com.example.hms.utility.MessageUtil;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

/**
 * QA D1000: a routing decision's reason in the reader's language.
 *
 * <p>The routing writes (print for the patient, back order) now store a
 * message key; older rows, and the SMS dispatch path, stored English
 * sentences ("Medication out of stock; placed on back order", "Dispatched by
 * SMS to PARTNER_PHARMACY X. Note: ...", "Superseded: re-dispatched by SMS to
 * X", "Not delivered: the SMS to the pharmacy failed"), which reached a French
 * or Spanish pharmacist as written. Each known shape is rendered here from a
 * bundle; the pharmacist's own words (a partner-routing reason, a dispatch
 * note) are kept as typed. Anything this class does not recognise is shown as
 * stored, never dropped.
 */
public final class RoutingReasonText {

    public static final String PRINT_KEY = "stock.routing.reason.print";
    public static final String BACK_ORDER_KEY = "stock.routing.reason.backOrder";

    /** The fixed English sentences stored before D1000, each with the key that now says it. */
    static final List<String[]> LEGACY_SENTENCES = List.of(
            new String[] {"Prescription printed for patient to fill at external pharmacy", PRINT_KEY},
            new String[] {"Medication out of stock; placed on back order", BACK_ORDER_KEY},
            new String[] {"Not delivered: the SMS to the pharmacy failed", "stock.routing.reason.smsFailed"});

    private static final String DISPATCHED_PREFIX = "Dispatched by SMS to ";
    private static final String SUPERSEDED_PREFIX = "Superseded: re-dispatched by SMS to ";
    private static final String NOTE_PREFIX = ". Note: ";
    private static final List<String> PHARMACY_TYPES =
            List.of("HOSPITAL_DISPENSARY", "PARTNER_PHARMACY", "COMMUNITY_PHARMACY");

    /**
     * The English fragments {@code PrescriptionSmsDispatchServiceImpl} composes
     * a reason from. {@code PharmacyUserFacingTextGuardTest} fails when that
     * service stores a fragment this list does not name.
     */
    public static final List<String> DISPATCH_FRAGMENTS = List.of(
            DISPATCHED_PREFIX,
            ". ",
            NOTE_PREFIX,
            SUPERSEDED_PREFIX,
            "Not delivered: the SMS to the pharmacy failed");

    /**
     * The separators a typed fragment runs up to. Found by index, not by a
     * regex: an alternation inside a repetition backtracks on a long typed
     * reason (CodeQL polynomial ReDoS) and recurses once per character
     * (Sonar S5998, stack overflow), both on #975.
     */
    private static final List<String> ANY_SEPARATOR = List.of(NOTE_PREFIX, ". Superseded: ", ". Not delivered: ");
    private static final List<String> SUPERSEDED_OR_FAILED = List.of(". Superseded: ", ". Not delivered: ");

    /** A recognised part of a stored reason: its marker at {@code start}, its typed fragment up to {@code end}. */
    private record Part(int start, int fragmentStart, int end) {
        String fragment(String text) {
            return text.substring(fragmentStart, end);
        }
    }

    private RoutingReasonText() {
    }

    /** The reason as the caller reads it, in the request's locale. */
    public static String readable(String stored) {
        return readable(stored, LocaleContextHolder.getLocale());
    }

    static String readable(String stored, Locale locale) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (MessageUtil.isMessageKey(stored)) {
            return orStored(MessageUtil.resolveOrNull(locale, stored), stored);
        }
        String text = stored;
        for (String[] legacy : LEGACY_SENTENCES) {
            text = replaceSentence(text, legacy[0], legacy[1], locale);
        }
        text = dispatched(text, locale);
        Part superseded = firstPart(text, SUPERSEDED_PREFIX, true, ANY_SEPARATOR);
        if (superseded != null) {
            text = text.substring(0, superseded.start())
                    + message(locale, "stock.routing.reason.smsSuperseded",
                            text.substring(superseded.start(), superseded.end()), superseded.fragment(text))
                    + text.substring(superseded.end());
        }
        Part note = firstPart(text, NOTE_PREFIX, false, SUPERSEDED_OR_FAILED);
        if (note != null) {
            // The ". " before "Note: " stays; "Note: <fragment>" is what is translated.
            int noteStart = note.start() + 2;
            text = text.substring(0, noteStart)
                    + message(locale, "stock.routing.reason.note", text.substring(noteStart, note.end()),
                            note.fragment(text))
                    + text.substring(note.end());
        }
        return text;
    }

    /** "Dispatched by SMS to &lt;TYPE&gt; &lt;pharmacy&gt;" at the very start of the reason, translated. */
    private static String dispatched(String text, Locale locale) {
        if (!text.startsWith(DISPATCHED_PREFIX)) {
            return text;
        }
        for (String type : PHARMACY_TYPES) {
            String head = DISPATCHED_PREFIX + type + " ";
            if (text.startsWith(head)) {
                int end = fragmentEnd(text, head.length(), ANY_SEPARATOR);
                if (end == head.length()) {
                    return text;
                }
                String label = MessageUtil.resolveOrNull(locale, "stock.routing.pharmacyType." + type);
                return message(locale, "stock.routing.reason.smsDispatched", text.substring(0, end),
                        orStored(label, type), text.substring(head.length(), end)) + text.substring(end);
            }
        }
        return text;
    }

    /**
     * The first {@code marker} followed by a non-empty fragment; with
     * {@code atSentenceStart}, only a marker that opens the reason or follows
     * ". ".
     */
    private static Part firstPart(String text, String marker, boolean atSentenceStart, List<String> separators) {
        int at = text.indexOf(marker);
        while (at >= 0) {
            boolean boundary = !atSentenceStart || at == 0 || (at >= 2 && text.startsWith(". ", at - 2));
            int fragmentStart = at + marker.length();
            int end = fragmentEnd(text, fragmentStart, separators);
            if (boundary && end > fragmentStart) {
                return new Part(at, fragmentStart, end);
            }
            at = text.indexOf(marker, at + 1);
        }
        return null;
    }

    /** Where the fragment starting at {@code from} ends: the first separator after it, or the end. */
    private static int fragmentEnd(String text, int from, List<String> separators) {
        int end = text.length();
        for (String separator : separators) {
            int at = text.indexOf(separator, from);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return end;
    }

    private static String replaceSentence(String text, String sentence, String key, Locale locale) {
        if (!text.contains(sentence)) {
            return text;
        }
        String translated = MessageUtil.resolveOrNull(locale, key);
        return translated == null ? text : text.replace(sentence, translated);
    }

    private static String message(Locale locale, String key, String fallback, Object... args) {
        if (MessageUtil.resolveOrNull(locale, key) == null) {
            return fallback;
        }
        return MessageUtil.resolve(locale, key, args);
    }

    private static String orStored(String translated, String stored) {
        return translated != null ? translated : stored;
    }
}
