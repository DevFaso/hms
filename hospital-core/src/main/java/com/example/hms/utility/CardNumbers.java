package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Billing-money plan, "New acceptance criterion" (owner, 2026-11-15): e-Keneya never collects card numbers, CVVs or
 * PINs. Card and mobile-money payments completed outside e-Keneya are recorded with their transaction reference only;
 * a value that looks like a card number in a payment or refund text field (a reference, a note, a reason) is refused
 * before it is stored, and the refusal never repeats it (nor is it logged).
 *
 * <p>"Looks like a card number": a run of 13 to 19 digits, single spaces or dashes allowed between them, whose digits
 * pass the Luhn check. A CVV or a PIN alone (3-4 digits) cannot be told from any other short number, so the forms say
 * never to type one ({@code BILLING.NO_CARD_DATA}); a card number is what can be detected.
 *
 * <p>{@code CardDataFreeTextGuardTest} keeps every payment and refund free-text request field on {@link
 * com.example.hms.validation.NoCardNumber}.
 */
public final class CardNumbers {

    /** The message key every refusal answers with (billing bundles). */
    public static final String REFUSED_KEY = "payment.cardData.refused";

    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;
    /** Digits with at most one space or dash between two of them. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d(?:[ -]?+\\d)*+");

    private CardNumbers() {
    }

    /** Whether {@code text} holds a value that looks like a payment card number. Null and blank hold none. */
    public static boolean containsCardNumber(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Matcher run = DIGIT_RUN.matcher(text);
        while (run.find()) {
            String digits = run.group().replace(" ", "").replace("-", "");
            if (digits.length() >= MIN_DIGITS && digits.length() <= MAX_DIGITS && luhn(digits)) {
                return true;
            }
        }
        return false;
    }

    /** Refuses {@code text} for {@code field} when it holds a card number; the refusal never repeats the value. */
    public static void requireNone(String text, String field) {
        if (containsCardNumber(text)) {
            throw new FieldBusinessException(field, REFUSED_KEY);
        }
    }

    /** The Luhn (mod 10) check over a string of digits. */
    static boolean luhn(String digits) {
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
