package com.example.hms.utility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The token of an e-mailed activation link. The link carries the token alone (no address), so the token is the
 * whole proof: 256 random bits, and only its SHA-256 is stored, as for a password-reset token. It stays single
 * use (cleared at activation) and short-lived (the expiry stamped beside it).
 *
 * <p>Links mailed before this rule hold a random UUID stored as sent; they keep working until they expire. Only a
 * stored value of that shape is ever compared as sent, so a stored hash is never accepted as a token.
 */
public final class ActivationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern LEGACY = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ActivationTokens() {
    }

    /** A new token: {@code raw} goes in the link and nowhere else, {@code stored} on the account. */
    public record Issued(String raw, String stored) {
        @Override
        public String toString() {
            return "Issued[token withheld]";
        }
    }

    public static Issued issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Issued(raw, sha256Hex(raw));
    }

    /** The stored values a presented token may be found under: its hash, and itself when it is an older link's. */
    public static List<String> storedForms(String presented) {
        if (presented == null || presented.isBlank()) {
            return List.of();
        }
        String token = presented.trim();
        return LEGACY.matcher(token).matches() ? List.of(sha256Hex(token), token) : List.of(sha256Hex(token));
    }

    /** Whether {@code presented} is the token behind {@code stored}. Constant-time on the stored value. */
    public static boolean matches(String stored, String presented) {
        if (stored == null || presented == null || presented.isBlank()) {
            return false;
        }
        String token = presented.trim();
        boolean hashed = same(stored, sha256Hex(token));
        boolean legacy = LEGACY.matcher(stored).matches() && same(stored, token);
        return hashed || legacy;
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
