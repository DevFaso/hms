package com.example.hms.testsupport;

import java.util.regex.Pattern;

/**
 * For the "this text never carries the amount / the code / the number" assertions. An audit
 * description, a log line or a record's {@code toString()} often names the row by its random
 * {@link java.util.UUID}, and 32 random hex digits contain a given 3-digit run like {@code "500"}
 * for about one id in 140: {@code doesNotContain("500")} on such a text is a coin that lands wrong
 * now and then (InvoiceRefundServiceTest and PharmacyPaymentVoiderTest blocked CI on 2026-11-21).
 *
 * <p>Assert on {@code withoutRandomIds(text)} instead: every UUID is replaced by {@value #PLACEHOLDER},
 * the rest of the text is kept as is, so {@code "500"}, {@code "1500"} or {@code "500F"} written
 * anywhere else still fails the assertion. Only UUID-shaped runs are removed: a card number, a phone
 * number or a hex fingerprint is never one, so a leak of those is still seen.
 * {@code architecture/RandomIdDigitAssertionGuardTest} keeps new negative digit assertions on this path.
 */
public final class RandomIds {

    /** What a UUID becomes: not a digit, and no letter run a test looks for. */
    public static final String PLACEHOLDER = "<uuid>";

    private static final Pattern UUID_SHAPE = Pattern.compile(
        "[0-9a-fA-F]{8}+-[0-9a-fA-F]{4}+-[0-9a-fA-F]{4}+-[0-9a-fA-F]{4}+-[0-9a-fA-F]{12}+");

    private RandomIds() {
    }

    /** {@code String.valueOf(text)} with every UUID replaced by {@link #PLACEHOLDER}. */
    public static String withoutRandomIds(Object text) {
        return UUID_SHAPE.matcher(String.valueOf(text)).replaceAll(PLACEHOLDER);
    }
}
