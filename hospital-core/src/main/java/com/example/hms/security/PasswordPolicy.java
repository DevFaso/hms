package com.example.hms.security;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.User;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rule every password a person types must meet, on every path that sets
 * one (registration, reset, change, first sign-in setup, a password an
 * administrator types for someone): at least {@link #MIN_LENGTH} characters,
 * not one of the most used passwords (the bundled list, also when a few
 * digits or signs are put around the word), not made of two or three repeated
 * characters, and not built on the account's own username, name, e-mail
 * address or phone number.
 *
 * <p>A refusal is a {@link FieldBusinessException} on the password field, so
 * a form marks the field and shows the reason. A password the server
 * generates is random and is not checked here.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    public static final String TOO_SHORT_KEY = "auth.password.tooShort";
    public static final String TOO_COMMON_KEY = "auth.password.tooCommon";
    public static final String CONTAINS_IDENTITY_KEY = "auth.password.containsIdentity";

    private static final String LIST = "security/common-passwords.txt";
    /** How many characters around a listed word still leave it the listed word ("soleil2026!"). */
    private static final int MAX_AFFIX = 6;
    private static final int MIN_DISTINCT_CHARACTERS = 4;
    private static final int MIN_IDENTITY_PART = 4;
    private static final int MIN_PHONE_DIGITS = 8;

    private PasswordPolicy() {
    }

    /** What is known of the account the password is for. Any part may be {@code null}. */
    public record Identity(String username, String firstName, String lastName, String email, String phoneNumber) {

        public static Identity of(User account) {
            return account == null
                ? new Identity(null, null, null, null, null)
                : new Identity(account.getUsername(), account.getFirstName(), account.getLastName(),
                    account.getEmail(), account.getPhoneNumber());
        }

        /** The same person under the username they are about to take. */
        public Identity withUsername(String newUsername) {
            return newUsername == null ? this : new Identity(newUsername, firstName, lastName, email, phoneNumber);
        }
    }

    /** Refuses {@code password} for {@code account} on the request property {@code field}. */
    public static void require(String field, String password, User account) {
        require(field, password, Identity.of(account));
    }

    /** Refuses {@code password} for {@code identity} on the request property {@code field}. */
    public static void require(String field, String password, Identity identity) {
        String problem = problemKey(password, identity);
        if (problem != null) {
            throw new FieldBusinessException(field, problem);
        }
    }

    /** The bundle key of what is wrong with {@code password}, or {@code null} when it is acceptable. */
    public static String problemKey(String password, Identity identity) {
        if (password == null || password.length() < MIN_LENGTH) {
            return TOO_SHORT_KEY;
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.chars().distinct().count() < MIN_DISTINCT_CHARACTERS || isCommon(lower)) {
            return TOO_COMMON_KEY;
        }
        return identity != null && containsIdentity(lower, identity) ? CONTAINS_IDENTITY_KEY : null;
    }

    private static boolean isCommon(String lower) {
        Set<String> common = Holder.COMMON;
        if (common.contains(lower)) {
            return true;
        }
        int start = 0;
        int end = lower.length();
        while (start < end && !Character.isLetter(lower.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetter(lower.charAt(end - 1))) {
            end--;
        }
        int removed = lower.length() - (end - start);
        return removed > 0 && removed <= MAX_AFFIX && common.contains(lower.substring(start, end));
    }

    private static boolean containsIdentity(String lower, Identity identity) {
        List<String> parts = new ArrayList<>();
        addParts(parts, identity.username());
        addParts(parts, identity.firstName());
        addParts(parts, identity.lastName());
        String email = identity.email();
        if (email != null) {
            int at = email.indexOf('@');
            addParts(parts, at > 0 ? email.substring(0, at) : email);
        }
        for (String part : parts) {
            if (lower.contains(part)) {
                return true;
            }
        }
        String phone = digits(identity.phoneNumber());
        if (phone.length() >= MIN_PHONE_DIGITS) {
            String national = phone.substring(phone.length() - MIN_PHONE_DIGITS);
            return digits(lower).contains(national);
        }
        return false;
    }

    /** The whole value and each word of it, lower case, when long enough to mean something. */
    private static void addParts(List<String> parts, String value) {
        if (value == null) {
            return;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.length() >= MIN_IDENTITY_PART) {
            parts.add(lower);
        }
        for (String word : lower.split("[\\s._\\-+']+")) {
            if (word.length() >= MIN_IDENTITY_PART) {
                parts.add(word);
            }
        }
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    /** Loaded on first use: a broken resource fails the first password set, loudly, not the start-up of a test. */
    private static final class Holder {
        static final Set<String> COMMON = load();

        private static Set<String> load() {
            Set<String> words = new HashSet<>(40_000);
            try (InputStream in = PasswordPolicy.class.getClassLoader().getResourceAsStream(LIST)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + LIST);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String word = line.trim();
                    if (!word.isEmpty() && word.charAt(0) != '#') {
                        words.add(word.toLowerCase(Locale.ROOT));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Set.copyOf(words);
        }
    }
}
