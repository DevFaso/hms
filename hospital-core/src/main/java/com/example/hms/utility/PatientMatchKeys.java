package com.example.hms.utility;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one definition of how a patient's name and phone number are compared
 * when the desk searches, checks for a duplicate or looks for a record to
 * link (QA D924, D942, D943). Every patient search and match path builds its
 * parameters here, and the repository queries fold the stored side with the
 * SQL fragments below, so both sides of every comparison are folded the same
 * way:
 *
 * <ul>
 *   <li><b>Names</b> are compared lower-cased and without accents:
 *       "Kabore" finds "Kaboré", "Aicha" finds "Aïcha".</li>
 *   <li><b>Phone numbers</b> are compared on their digits only, and on the
 *       national subscriber number (the last eight digits) when the typed
 *       number has that many: "+22670001002", "70 00 10 02" and "70001002"
 *       are the same subscriber, however the number was stored.</li>
 * </ul>
 *
 * {@code PatientMatchKeysGuardTest} fails the build when a patient query
 * compares a name or phone column without these folds.
 */
public final class PatientMatchKeys {

    /**
     * Accented letters folded by {@link #NAME_FOLD_TO} in SQL (TRANSLATE maps
     * one character to one character). Lower-case only: the column is
     * lower-cased first.
     */
    public static final String NAME_FOLD_FROM = "àáâãäåāèéêëēìíîïīòóôõöōùúûüūýÿçñ";

    /** The plain letter of each character of {@link #NAME_FOLD_FROM}, in order. */
    public static final String NAME_FOLD_TO = "aaaaaaaeeeeeiiiiioooooouuuuuyycn";

    /**
     * The SQL that reduces a phone column to its digits:
     * {@code DIGITS_SQL_OPEN + "p.phoneNumberPrimary" + DIGITS_SQL_CLOSE}.
     * One definition, so every repository folds the same separators.
     */
    public static final String DIGITS_SQL_OPEN = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(";

    /** Closes {@link #DIGITS_SQL_OPEN}. */
    public static final String DIGITS_SQL_CLOSE =
        ", ' ', ''), '+', ''), '-', ''), '.', ''), '(', ''), ')', ''), '/', '')";

    /** National subscriber numbers are eight digits in the deployment country. */
    public static final int SUBSCRIBER_DIGITS = 8;

    /** A value with no digit at all is not a phone search. */
    private static final int MIN_PHONE_DIGITS = 1;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    private static final Pattern PHONE_SHAPED = Pattern.compile("\\+?[\\d\\s().\\-/]{6,}");
    /** {@code yyyy-mm-dd}, as a date input and the API send it. */
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    /** {@code dd/mm/yyyy}, {@code dd-mm-yyyy} or {@code dd.mm.yyyy}, as the desk types a birth date. */
    private static final Pattern DAY_FIRST_DATE = Pattern.compile("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})");

    private PatientMatchKeys() {
    }

    /**
     * A name as it is compared: accents removed, lower-cased, inner spaces
     * collapsed, trimmed. {@code null} for a blank value.
     */
    public static String foldName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // Exactly the letters the SQL TRANSLATE folds, and no others: a name
        // with another accented letter ("Şahin") stays as typed on both
        // sides, so it is still found as written.
        String lower = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int at = NAME_FOLD_FROM.indexOf(c);
            out.append(at >= 0 ? NAME_FOLD_TO.charAt(at) : c);
        }
        return SPACES.matcher(out).replaceAll(" ");
    }

    /**
     * The LIKE pattern a name search binds: the folded name, its LIKE
     * wildcards escaped, inside {@code %…%}. {@code null} for a blank value.
     */
    public static String nameContainsPattern(String value) {
        String folded = foldName(value);
        return folded == null ? null : "%" + escapeLike(folded) + "%";
    }

    /**
     * The digits a phone number is compared on: digits only, an international
     * {@code 00} dropped, and the last {@value #SUBSCRIBER_DIGITS} when there
     * are at least that many. {@code null} when no digit remains (not a phone
     * number).
     */
    public static String phoneKey(String value) {
        if (value == null) {
            return null;
        }
        String digits = NON_DIGITS.matcher(value).replaceAll("");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() < MIN_PHONE_DIGITS) {
            return null;
        }
        return digits.length() > SUBSCRIBER_DIGITS
            ? digits.substring(digits.length() - SUBSCRIBER_DIGITS)
            : digits;
    }

    /**
     * The LIKE pattern a phone search binds against the digits-only column:
     * {@code %key%}. {@code null} when the value is not a phone number.
     */
    public static String phoneContainsPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String key = phoneKey(value);
        // A phone filter with no digit in it matches nothing (the column is
        // digits only), never everything: null would drop the filter.
        return key == null ? "%" + escapeLike(value.trim()) + "%" : "%" + key + "%";
    }

    /**
     * Whether a typed number is specific enough to identify a subscriber
     * (all eight national digits), as a match or duplicate check needs.
     */
    public static boolean isSubscriberKey(String key) {
        return key != null && key.length() == SUBSCRIBER_DIGITS;
    }

    /**
     * Whether two numbers, however written, are the same subscriber: their
     * full international digits are equal, a national number taking the
     * deployment's calling code (Burkina Faso). The eight-digit key only
     * narrows the candidates in SQL; this decides, so +225 07 01 23 45 67 and
     * +225 05 01 23 45 67 (same last eight digits) are two subscribers.
     */
    public static boolean samePhone(String a, String b) {
        return samePhone(a, b, PhoneNumbers.DEFAULT_CALLING_CODE);
    }

    /**
     * Whether two numbers are the same subscriber at a hospital whose calling
     * code is {@code callingCode}: a national number takes that code (its
     * trunk zero dropped outside Burkina Faso, as PhoneNumbers.toE164 stores
     * it), then the full international digits must be equal. A hospital in
     * Côte d'Ivoire stored "07 01 23 45 67" as +225701234567, and typed again
     * nationally it is the same subscriber; a Burkina "70001002" is never an
     * Ivorian +225770001002 that merely ends alike.
     */
    public static boolean samePhone(String a, String b, String callingCode) {
        if (!isSubscriberKey(phoneKey(a)) || !isSubscriberKey(phoneKey(b))) {
            return false;
        }
        String code = callingCode == null || callingCode.isBlank() ? PhoneNumbers.DEFAULT_CALLING_CODE : callingCode;
        String ca = international(a, code);
        String cb = international(b, code);
        return !ca.isEmpty() && ca.equals(cb);
    }

    /** The full international digits of a number, a national one taking {@code code}. */
    private static String international(String value, String code) {
        if (isInternational(value)) {
            return digitsOf(value);
        }
        String national = digitsOf(value);
        // Written with its country code but no '+' ("226 70 00 10 02", a legacy
        // "22670001002"): already international, never prefixed twice (review
        // round 14). No national number is that long after the code.
        if (national.startsWith(code) && national.length() - code.length() >= SUBSCRIBER_DIGITS) {
            return national;
        }
        if (national.startsWith("0") && !PhoneNumbers.DEFAULT_CALLING_CODE.equals(code)) {
            national = national.substring(1);
        }
        return code + national;
    }

    /** Written with its country: a leading + or 00. */
    private static boolean isInternational(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("+") || NON_DIGITS.matcher(trimmed).replaceAll("").startsWith("00");
    }

    /** Digits only, an international 00 dropped. */
    private static String digitsOf(String value) {
        String digits = NON_DIGITS.matcher(value).replaceAll("");
        return digits.startsWith("00") ? digits.substring(2) : digits;
    }

    /**
     * Whether a free-text identifier is written like a phone number (digits
     * and the separators a desk types, nothing else). An e-mail address or an
     * MRN with digits in it is not, and is never looked up by its digits.
     */
    public static boolean looksLikePhone(String value) {
        // QA D1274: "1993-11-03" and "03/11/1993" are made of digits and
        // separators too; a birth date is searched as a date, never by its digits.
        return value != null && PHONE_SHAPED.matcher(value.trim()).matches() && birthDate(value) == null;
    }

    /**
     * QA D1274 (round 27): a birth date typed into a patient search, in any
     * format the pickers accept - {@code yyyy-mm-dd}, {@code dd/mm/yyyy},
     * {@code dd-mm-yyyy} or {@code dd.mm.yyyy} (day first, as written in the
     * deployment countries). {@code null} when the value is not a real date
     * written that way, so a phone number or a name is never read as one.
     */
    public static LocalDate birthDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Matcher iso = ISO_DATE.matcher(trimmed);
        if (iso.matches()) {
            return dateOf(iso.group(1), iso.group(2), iso.group(3));
        }
        Matcher dayFirst = DAY_FIRST_DATE.matcher(trimmed);
        if (dayFirst.matches()) {
            return dateOf(dayFirst.group(3), dayFirst.group(2), dayFirst.group(1));
        }
        return null;
    }

    private static LocalDate dateOf(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * QA D1274: a date-of-birth search value as the repository compares it
     * ({@code yyyy-mm-dd}): any accepted format is normalised; a value that is
     * not a date is kept as typed, so it still finds nobody rather than
     * dropping the filter. {@code null} for a blank value.
     */
    public static String birthDateKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate date = birthDate(value);
        return date != null ? date.toString() : value.trim();
    }

    /** QA D1274: whether a typed search term is an e-mail address (or the start of one, with its {@code @}). */
    public static boolean looksLikeEmail(String value) {
        return value != null && value.indexOf('@') >= 0;
    }

    /**
     * QA D1061 / D1064: whether two registrations name the same PERSON, the way
     * a registration desk decides it: the same first and last name (accents,
     * case and spacing aside; either order, since a surname is often written
     * first) and, when both carry one, the same date of birth. A shared phone
     * or e-mail alone never makes two people one: a child is registered under
     * a parent's number. The middle name is left out on purpose: it is often
     * missing from one of the two registrations.
     */
    public static boolean sameIdentity(String firstA, String lastA, java.time.LocalDate dobA,
                                       String firstB, String lastB, java.time.LocalDate dobB) {
        String fa = foldName(firstA);
        String la = foldName(lastA);
        String fb = foldName(firstB);
        String lb = foldName(lastB);
        if (fa == null || la == null || fb == null || lb == null) {
            return false;
        }
        boolean sameNames = (fa.equals(fb) && la.equals(lb)) || (fa.equals(lb) && la.equals(fb));
        return sameNames && (dobA == null || dobB == null || dobA.equals(dobB));
    }

    /** {@link #sameIdentity} against a stored patient record. */
    public static boolean sameIdentity(com.example.hms.model.Patient stored, String first, String last,
                                       java.time.LocalDate dob) {
        return stored != null && sameIdentity(stored.getFirstName(), stored.getLastName(), stored.getDateOfBirth(),
            first, last, dob);
    }

    /** Whether the folded {@code haystack} contains the folded {@code needle}. */
    public static boolean nameContains(String haystack, String needle) {
        String h = foldName(haystack);
        String n = foldName(needle);
        return h != null && n != null && h.contains(n);
    }

    /**
     * The SQL twin of {@link #escapeLike}: wraps a bound text so its LIKE
     * wildcards ('%', '_') and the escape character are literal, for a query
     * that builds its pattern in SQL (review round 14). The default LIKE escape
     * is the backslash on PostgreSQL and H2.
     */
    public static final String LIKE_ESCAPE_SQL_OPEN = "REPLACE(REPLACE(REPLACE(";
    /** Closes {@link #LIKE_ESCAPE_SQL_OPEN}. */
    public static final String LIKE_ESCAPE_SQL_CLOSE = ", '\\', '\\\\'), '%', '\\%'), '_', '\\_')";

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
