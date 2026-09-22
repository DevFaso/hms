package com.example.hms.utility;

/**
 * The one definition of a phone number's wire form.
 *
 * <p>HMS stores numbers exactly as typed at the desk ({@code "70 70 70 70"},
 * {@code "+226 70 70 70 70"}, {@code "0022670707070"}). The SMS gateway sends to
 * the international number without {@code +} ({@code 22670707070}), so a reply
 * comes back from that form and must compare equal to whatever was stored.
 * Both sides go through here.
 */
public final class PhoneNumbers {

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
}
