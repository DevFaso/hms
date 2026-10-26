package com.example.hms.utility;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one definition of a phone number's wire form, and of when two spellings
 * of a number are the same subscriber.
 *
 * <p>HMS stores numbers exactly as typed at the desk ({@code "70 70 70 70"},
 * {@code "+226 70 70 70 70"}, {@code "0022670707070"}). The SMS gateway sends to
 * the international number without {@code +} ({@code 22670707070}), so a reply
 * comes back from that form and must compare equal to whatever was stored.
 *
 * <p>A desk also types things a canonical form cannot survive: two numbers in
 * one field, or an extension after the number. Those used to canonicalise to a
 * long digit string that matched nothing, so every reply from that pharmacy was
 * refused — and, once the unmatched sender was alerted on, raised a security
 * audit row for each one. {@link #isSameSubscriber} understands both shapes.
 */
public final class PhoneNumbers {

    /** Separators a desk uses between two numbers in one field. */
    private static final Pattern NUMBER_SEPARATORS = Pattern.compile("[,;/|\\n]+|\\bou\\b");

    /** Everything from an extension marker onwards is not part of the number. */
    private static final Pattern EXTENSION_MARKER =
            Pattern.compile("(?i)\\b(poste|extension|ext)\\.?\\s*\\d+.*$");

    /** National subscriber numbers are 8 digits in the deployment country. */
    private static final int SIGNIFICANT_DIGITS = 8;

    /** Longer than any plausible international number: the field holds more than one. */
    private static final int MAX_PLAUSIBLE_DIGITS = 13;

    /** Calling code used when the hospital's country is unknown: Burkina Faso. */
    public static final String DEFAULT_CALLING_CODE = "226";

    /** Country (ISO alpha-2 or name, lower-cased, accents kept) to calling code. */
    private static final java.util.Map<String, String> CALLING_CODES = java.util.Map.ofEntries(
            java.util.Map.entry("bf", "226"), java.util.Map.entry("burkina faso", "226"),
            java.util.Map.entry("ml", "223"), java.util.Map.entry("mali", "223"),
            java.util.Map.entry("ci", "225"), java.util.Map.entry("côte d'ivoire", "225"),
            java.util.Map.entry("cote d'ivoire", "225"), java.util.Map.entry("ivory coast", "225"),
            java.util.Map.entry("ne", "227"), java.util.Map.entry("niger", "227"),
            java.util.Map.entry("tg", "228"), java.util.Map.entry("togo", "228"),
            java.util.Map.entry("bj", "229"), java.util.Map.entry("benin", "229"),
            java.util.Map.entry("bénin", "229"), java.util.Map.entry("sn", "221"),
            java.util.Map.entry("senegal", "221"), java.util.Map.entry("sénégal", "221"),
            java.util.Map.entry("gh", "233"), java.util.Map.entry("ghana", "233"),
            java.util.Map.entry("fr", "33"), java.util.Map.entry("france", "33"));

    /** E.164: a plus, then 8 to 15 digits, the first not zero. */
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    /** Characters a desk types between digit groups. */
    private static final Pattern FORMATTING = Pattern.compile("[\\s.\\-()/]");

    private PhoneNumbers() {
    }

    /** A national number typed without a prefix: digits only, 6 to 15 of them. */
    private static final Pattern NATIONAL_DIGITS = Pattern.compile("\\d{6,15}");

    /** Whether {@code country} is one whose calling code this class knows. */
    public static boolean isKnownCountry(String country) {
        if (country == null || country.isBlank()) {
            return false;
        }
        return CALLING_CODES.containsKey(
                country.trim().toLowerCase(java.util.Locale.ROOT).replace('\u2019', '\''));
    }

    /**
     * QA D833: a facility's phone number as it is stored, or {@code null} when
     * it is not a phone number. Typed as {@code "+226 25 36 12 40"} it becomes
     * {@code "+22625361240"}: the separators a desk types are not part of the
     * number, and counting them against a length limit cut the last digit off.
     * A number for a country this class knows, or one written with an
     * international prefix, must be a valid E.164 number; a national number
     * for any other country is kept as its digits.
     */
    public static String normalizeFacilityPhone(String raw, String country) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String e164 = toE164(raw, callingCodeForCountry(country));
        if (e164 != null) {
            return e164;
        }
        String compact = FORMATTING.matcher(raw.trim()).replaceAll("");
        boolean international = compact.startsWith("+") || compact.startsWith("00");
        if (international || isKnownCountry(country) || !NATIONAL_DIGITS.matcher(compact).matches()) {
            return null;
        }
        return compact;
    }

    /** The calling code (digits, no plus) for a hospital country; Burkina Faso when unknown. */
    public static String callingCodeForCountry(String country) {
        if (country == null || country.isBlank()) {
            return DEFAULT_CALLING_CODE;
        }
        String key = country.trim().toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
        return CALLING_CODES.getOrDefault(key, DEFAULT_CALLING_CODE);
    }

    /**
     * One number typed at a desk, in E.164 ({@code +22670202020}), or
     * {@code null} when it cannot be one. A local number ({@code 70202020},
     * {@code 0612345678}) takes {@code callingCode}, its trunk zero dropped;
     * {@code 00} becomes {@code +}. Burkina Faso numbers must have exactly
     * eight national digits.
     */
    public static String toE164(String raw, String callingCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = FORMATTING.matcher(raw.trim()).replaceAll("");
        String candidate;
        if (compact.startsWith("+")) {
            candidate = compact;
        } else if (compact.startsWith("00")) {
            candidate = "+" + compact.substring(2);
        } else if (compact.matches("\\d+")) {
            String national = compact.startsWith("0") && !DEFAULT_CALLING_CODE.equals(callingCode)
                    ? compact.substring(1) : compact;
            candidate = "+" + callingCode + national;
        } else {
            return null;
        }
        if (!E164.matcher(candidate).matches()) {
            return null;
        }
        if (candidate.startsWith("+" + DEFAULT_CALLING_CODE)
                && candidate.length() != 1 + DEFAULT_CALLING_CODE.length() + SIGNIFICANT_DIGITS) {
            return null;
        }
        return candidate;
    }

    /**
     * Digits only, {@code 00} international prefix stripped, and a local-format
     * number (10 digits or fewer) given the configured country code — exactly
     * what the gateway puts on the wire. Empty when nothing usable remains.
     */
    public static String toInternationalDigits(String raw, String countryNumberCode) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (!digits.isEmpty() && digits.length() <= 10) {
            digits = countryNumberCode + digits;
        }
        return digits;
    }

    /**
     * Whether {@code storedField} — which may hold several numbers, or one with
     * an extension — names the same subscriber as {@code incoming}.
     *
     * <p>Comparison is on the last {@value #SIGNIFICANT_DIGITS} digits, the
     * national subscriber number: that is the part a country code, a trunk
     * prefix or a stray leading zero cannot change. It is deliberately not the
     * whole string, and deliberately not fewer digits — eight is specific
     * enough that two pharmacies at one hospital will not collide, while
     * tolerating how the same number gets written down.
     */
    public static boolean isSameSubscriber(String storedField, String incoming, String countryNumberCode) {
        String incomingCanonical = toInternationalDigits(incoming, countryNumberCode);
        if (incomingCanonical.isEmpty()) {
            return false;
        }
        String incomingTail = significantTail(incomingCanonical);
        for (String candidate : candidates(storedField, countryNumberCode)) {
            if (candidate.equals(incomingCanonical)) {
                return true;
            }
            String tail = significantTail(candidate);
            if (!tail.isEmpty() && tail.equals(incomingTail)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every number a stored field holds, in wire form: split on the separators a
     * desk uses, extensions dropped, and a part that is still too long to be one
     * number split again on whitespace.
     */
    public static List<String> candidates(String storedField, String countryNumberCode) {
        if (storedField == null || storedField.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : NUMBER_SEPARATORS.split(storedField)) {
            String withoutExtension = EXTENSION_MARKER.matcher(part).replaceFirst("");
            String canonical = toInternationalDigits(withoutExtension, countryNumberCode);
            if (canonical.length() > MAX_PLAUSIBLE_DIGITS) {
                // Two numbers separated by nothing but a space: "70707070 70111222".
                for (String piece : withoutExtension.trim().split("\\s+")) {
                    String pieceCanonical = toInternationalDigits(piece, countryNumberCode);
                    if (significantTail(pieceCanonical).length() == SIGNIFICANT_DIGITS) {
                        out.add(pieceCanonical);
                    }
                }
            }
            if (!canonical.isEmpty()) {
                out.add(canonical);
            }
        }
        return new ArrayList<>(out);
    }

    /** The last {@value #SIGNIFICANT_DIGITS} digits, or "" when there are not that many. */
    private static String significantTail(String canonical) {
        if (canonical == null || canonical.length() < SIGNIFICANT_DIGITS) {
            return "";
        }
        return canonical.substring(canonical.length() - SIGNIFICANT_DIGITS);
    }
}
