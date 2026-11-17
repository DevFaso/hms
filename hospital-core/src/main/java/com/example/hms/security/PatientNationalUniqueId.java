package com.example.hms.security;

import com.example.hms.exception.FieldBusinessException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** The IU is optional identity data, never a numeric value or a public lookup token. */
public final class PatientNationalUniqueId {
    private static final String HMAC = "HmacSHA256";
    private static final String FIELD = "nationalUniqueId";
    private static final byte[] LOOKUP_DOMAIN = "hms.patient.national-iu.lookup.v1".getBytes(StandardCharsets.UTF_8);
    private static final String MASK = "•••• •••• ";

    private PatientNationalUniqueId() {
    }

    public static String normalize(String input) {
        if (input == null) return null;
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);
            if (isSeparator(character)) continue;
            if (!isDigit(character) || value.length() == 12) throw invalid();
            value.append(character);
        }
        if (value.isEmpty()) return null;
        if (value.length() != 12) throw invalid();
        return value.toString();
    }

    public static String fingerprint(String input) {
        String value = normalize(input);
        if (value == null) return null;
        SecretKey master = EncryptionKeyHolder.getKey();
        if (master == null) {
            throw new FieldBusinessException(FIELD, "patient.iu.unavailable");
        }
        try {
            // Separate this lookup key from the AES-GCM encryption operation.
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(master.getEncoded(), HMAC));
            byte[] lookupKey = mac.doFinal(LOOKUP_DOMAIN);
            mac.init(new SecretKeySpec(lookupKey, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new FieldBusinessException(FIELD, "patient.iu.unavailable");
        }
    }

    public static String masked(String input) {
        String value = normalize(input);
        return value == null ? null : MASK + value.substring(8);
    }

    public static String formatted(String input) {
        String value = normalize(input);
        return value == null ? null : value.substring(0, 4) + " " + value.substring(4, 8) + " " + value.substring(8);
    }

    /** Reasons remain human-authored; identifier-like values never enter the audit payload. */
    public static String auditReason(String reason) {
        if (reason == null) return null;
        return redactNumbers(redactHashes(reason)).trim();
    }

    private static String redactHashes(String reason) {
        StringBuilder safe = new StringBuilder();
        int index = 0;
        while (index < reason.length()) {
            int start = index;
            while (index < reason.length() && isHex(reason.charAt(index))) index++;
            if (index - start >= 64) safe.append("[redacted]");
            else safe.append(reason, start, index);
            if (index < reason.length()) safe.append(reason.charAt(index++));
        }
        return safe.toString();
    }

    private static String redactNumbers(String reason) {
        StringBuilder safe = new StringBuilder();
        int index = 0;
        while (index < reason.length()) {
            if (!isDigit(reason.charAt(index))) {
                safe.append(reason.charAt(index++));
                continue;
            }
            int start = index;
            int digits = 0;
            int lastDigitEnd = index;
            while (index < reason.length() && (isDigit(reason.charAt(index)) || isSeparator(reason.charAt(index)))) {
                if (isDigit(reason.charAt(index++))) {
                    digits++;
                    lastDigitEnd = index;
                }
            }
            if (digits >= 12) {
                safe.append("[redacted]").append(reason, lastDigitEnd, index);
            } else {
                safe.append(reason, start, index);
            }
        }
        return safe.toString();
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isHex(char character) {
        return isDigit(character) || (character >= 'a' && character <= 'f') || (character >= 'A' && character <= 'F');
    }

    private static boolean isSeparator(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
            || Character.getType(character) == Character.DASH_PUNCTUATION;
    }

    private static FieldBusinessException invalid() {
        return new FieldBusinessException(FIELD, "patient.iu.invalid");
    }
}
