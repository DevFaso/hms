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

    private PhoneNumbers() {
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
