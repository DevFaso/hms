package com.example.hms.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validator behind {@link NoCardNumber}. */
public class NoCardNumberValidator implements ConstraintValidator<NoCardNumber, String> {

    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;

    /** A run of digit groups joined by single spaces or dashes ("4111 1111-1111 1111"). */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d++(?:[ \\-]\\d++)*+");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return !containsCardNumber(value);
    }

    /**
     * Whether {@code value} holds something that looks like a payment card
     * number: 13 to 19 digits, written together or in groups separated by a
     * space or a dash, that pass the Luhn check. Every whole-group window of a
     * run is tried, so "4111 1111 1111 1111 2025" (a card followed by an
     * expiry) is caught, while a long all-digit reference is not split into
     * arbitrary slices.
     */
    public static boolean containsCardNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Matcher run = DIGIT_RUN.matcher(value);
        while (run.find()) {
            if (anyWindowIsCard(groupsOf(run.group()))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> groupsOf(String run) {
        List<String> groups = new ArrayList<>();
        for (String group : run.split("[ \\-]")) {
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    private static boolean anyWindowIsCard(List<String> groups) {
        for (int from = 0; from < groups.size(); from++) {
            StringBuilder digits = new StringBuilder();
            for (int to = from; to < groups.size() && digits.length() < MAX_DIGITS; to++) {
                digits.append(groups.get(to));
                if (digits.length() >= MIN_DIGITS && digits.length() <= MAX_DIGITS && passesLuhn(digits)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean passesLuhn(CharSequence digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubled) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
