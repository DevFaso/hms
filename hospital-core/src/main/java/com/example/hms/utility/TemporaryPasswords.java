package com.example.hms.utility;

import java.security.SecureRandom;

/**
 * The one-time password an account is created with, or re-issued with at its first activation (QA r70 D3201):
 * 14 characters, at least one upper case letter, one lower case letter, one digit and one symbol, in a random
 * order. The holder replaces it at first sign-in ({@code forcePasswordChange}).
 */
public final class TemporaryPasswords {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%&*";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final int LENGTH = 14;

    private TemporaryPasswords() {
    }

    public static String generate() {
        // Guarantee one from each category
        StringBuilder sb = new StringBuilder(LENGTH);
        sb.append(UPPER.charAt(SECURE_RANDOM.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(SECURE_RANDOM.nextInt(LOWER.length())));
        sb.append(DIGITS.charAt(SECURE_RANDOM.nextInt(DIGITS.length())));
        sb.append(SPECIAL.charAt(SECURE_RANDOM.nextInt(SPECIAL.length())));
        for (int i = 4; i < LENGTH; i++) {
            sb.append(ALL.charAt(SECURE_RANDOM.nextInt(ALL.length())));
        }
        // Shuffle to avoid predictable positions
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
