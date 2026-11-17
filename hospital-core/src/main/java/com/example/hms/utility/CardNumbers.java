package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.validation.NoCardNumber;
import com.example.hms.validation.NoCardNumberValidator;

/**
 * Billing plan, owner decision D-15 (2026-11-15): e-Keneya never collects card numbers, CVVs or PINs. The service-side
 * twin of {@link NoCardNumber}: one rule ({@link NoCardNumberValidator#containsCardNumber}, the Luhn check) and one
 * refusal key ({@link NoCardNumber#MESSAGE_KEY}), for a service that refuses a value outside bean validation (the
 * refund service checks its texts again). The refusal names the field and never repeats the value.
 */
public final class CardNumbers {

    private CardNumbers() {
    }

    /** Whether {@code text} holds a value that looks like a payment card number. Null and blank hold none. */
    public static boolean containsCardNumber(String text) {
        return NoCardNumberValidator.containsCardNumber(text);
    }

    /** Refuses {@code text} for {@code field} when it holds a card number; the refusal never repeats the value. */
    public static void requireNone(String text, String field) {
        if (containsCardNumber(text)) {
            throw new FieldBusinessException(field, NoCardNumber.MESSAGE_KEY);
        }
    }
}
