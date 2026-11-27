package com.example.hms.service.integration;

import com.example.hms.utility.MessageUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * QA D4078 (class): why an instrument message was not delivered, in the reader's
 * language. The outbox row keeps what the attempt produced; a transport failure
 * is stored as the text of its exception, which the laboratory page must not
 * show. Each stored shape is read as a cause the bundles have words for.
 */
public final class InstrumentOutboxErrorText {

    private static final String NEGATIVE_ACK = "Negative acknowledgement: ";
    private static final Pattern EXCEPTION_TEXT = Pattern.compile("^[A-Za-z.$]*(Exception|Error)\\b.*", Pattern.DOTALL);

    private InstrumentOutboxErrorText() {
    }

    /** The stored failure as a sentence in {@code locale}; null stays null. */
    public static String describe(String stored, Locale locale) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        String cause = causeOf(stored);
        if (cause == null) {
            return stored;
        }
        String ack = stored.startsWith(NEGATIVE_ACK) ? stored.substring(NEGATIVE_ACK.length()) : "";
        String text = MessageUtil.resolveOrNull(locale, "instrument.outbox.error." + cause, new Object[] {ack});
        if (text != null) {
            return text;
        }
        return ack.isEmpty() ? cause : cause + ": " + ack;
    }

    private static String causeOf(String stored) {
        if (stored.startsWith("Not sent: the lab order was cancelled")) {
            return "ORDER_CANCELLED";
        }
        if (stored.startsWith("Not sent: the lab order was entered in error")) {
            return "ORDER_ENTERED_IN_ERROR";
        }
        if (stored.startsWith(NEGATIVE_ACK)) {
            return "NEGATIVE_ACK";
        }
        if (!EXCEPTION_TEXT.matcher(stored).matches()) {
            return null;
        }
        String text = stored.toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) {
            return "TIMEOUT";
        }
        if (text.contains("unknownhost") || text.contains("unresolved")) {
            return "HOST_NOT_FOUND";
        }
        if (text.contains("connection refused") || text.contains("connectexception")) {
            return "CONNECTION_REFUSED";
        }
        return "UNREACHABLE";
    }
}
