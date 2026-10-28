package com.example.hms.mapper.pharmacy;

import com.example.hms.utility.MessageUtil;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * The English fragments {@code PrescriptionSmsDispatchServiceImpl} composes
     * a reason from. {@code PharmacyUserFacingTextGuardTest} fails when that
     * service stores a fragment this list does not name.
     */
    public static final List<String> DISPATCH_FRAGMENTS = List.of(
            "Dispatched by SMS to ",
            ". ",
            ". Note: ",
            "Superseded: re-dispatched by SMS to ",
            "Not delivered: the SMS to the pharmacy failed");

    /**
     * A fragment up to the next ". Note: " / ". Superseded: " / ". Not delivered: "
     * or the end. Written as "any non-period, or a period not starting a
     * separator" rather than a lazy {@code (.+?)} with a lookahead: the two
     * branches cannot match the same character, so a pharmacist's long typed
     * reason is read in one pass (CodeQL polynomial-ReDoS on #975).
     */
    private static final String UNTIL_ANY_SEPARATOR =
            "((?:[^.]|\\.(?! (?:Note: |Superseded: |Not delivered: )))+)";
    private static final String UNTIL_SUPERSEDED_OR_FAILED =
            "((?:[^.]|\\.(?! (?:Superseded: |Not delivered: )))+)";
    private static final Pattern DISPATCHED = Pattern.compile(
            "^Dispatched by SMS to (HOSPITAL_DISPENSARY|PARTNER_PHARMACY|COMMUNITY_PHARMACY) " + UNTIL_ANY_SEPARATOR);
    private static final Pattern NOTE = Pattern.compile("\\. Note: " + UNTIL_SUPERSEDED_OR_FAILED);
    private static final Pattern SUPERSEDED = Pattern.compile(
            "(^|\\. )Superseded: re-dispatched by SMS to " + UNTIL_ANY_SEPARATOR);

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
        Matcher dispatched = DISPATCHED.matcher(text);
        if (dispatched.find()) {
            String type = MessageUtil.resolveOrNull(locale, "stock.routing.pharmacyType." + dispatched.group(1));
            text = dispatched.replaceFirst(Matcher.quoteReplacement(
                    message(locale, "stock.routing.reason.smsDispatched", dispatched.group(0),
                            orStored(type, dispatched.group(1)), dispatched.group(2))));
        }
        Matcher superseded = SUPERSEDED.matcher(text);
        if (superseded.find()) {
            text = superseded.replaceFirst(Matcher.quoteReplacement(superseded.group(1)
                    + message(locale, "stock.routing.reason.smsSuperseded",
                            superseded.group(0).substring(superseded.group(1).length()), superseded.group(2))));
        }
        Matcher note = NOTE.matcher(text);
        if (note.find()) {
            text = note.replaceFirst(Matcher.quoteReplacement(". "
                    + message(locale, "stock.routing.reason.note", note.group(0).substring(2), note.group(1))));
        }
        return text;
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
