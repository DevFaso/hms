package com.example.hms.service.integration.health;

import com.example.hms.utility.MessageUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * QA D4303 / D4078: what an integration says went wrong, in the reader's language.
 *
 * <p>A diagnostic is stored once and read by people in different languages, so
 * what is stored is a message key (with {@code |}-separated arguments when it has
 * any), never a sentence and never the text of an exception. It is turned into
 * words when it is read. Rows written before this carried English sentences;
 * the ones the product itself produced are recognised and read through the same
 * keys. Anything else (a partner's own answer) is shown as it was received.
 */
public final class IntegrationDiagnosticText {

    private static final String PREFIX = "integration.diagnostic.";
    private static final String SEPARATOR = "|";

    /** The connector is a placeholder: no partner is connected behind it. */
    public static final String NOT_CONNECTED = "integration.diagnostic.notConnected";
    /** The integration is switched off in the platform configuration. */
    public static final String DISABLED = "integration.diagnostic.disabled";
    /** Configured and switched on; nothing more can be checked without a partner call. */
    public static final String CONFIGURED = "integration.diagnostic.configured";
    /** The test stopped on a technical error; the detail is in the server log. */
    public static final String PROBE_ERROR = "integration.diagnostic.probeError";
    /** The re-synchronisation stopped on a technical error. */
    public static final String RESYNC_ERROR = "integration.diagnostic.resyncError";
    /** The test answered nothing. */
    public static final String PROBE_NO_ANSWER = "integration.diagnostic.probeNoAnswer";
    /** The connection test answered. */
    public static final String PROBE_OK = "integration.diagnostic.probeOk";

    private IntegrationDiagnosticText() {
    }

    /** True for a diagnostic that only says nothing is connected or switched on. */
    public static boolean isConfigurationState(String stored) {
        String key = keyOf(stored);
        return NOT_CONNECTED.equals(key) || DISABLED.equals(key);
    }

    /** The stored diagnostic in {@code locale}; null stays null. */
    public static String localize(String stored, Locale locale) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        String key = keyOf(stored);
        if (key == null) {
            return stored;
        }
        String[] parts = stored.startsWith(PREFIX)
            ? stored.split(Pattern.quote(SEPARATOR))
            : new String[] {key};
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        try {
            String text = MessageUtil.resolveOrNull(locale, key, args);
            return text != null ? text : stored;
        } catch (RuntimeException ex) {
            return stored;
        }
    }

    /** The key a stored diagnostic stands for, or null when it is somebody else's text. */
    private static String keyOf(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith(PREFIX)) {
            int bar = stored.indexOf(SEPARATOR);
            return bar < 0 ? stored : stored.substring(0, bar);
        }
        // Sentences this product stored before it stored keys.
        if (stored.startsWith("Stub-mode probe") || stored.startsWith("Connector in stub mode")) {
            return NOT_CONNECTED;
        }
        if (stored.startsWith("Probe threw ")) {
            return PROBE_ERROR;
        }
        if (stored.startsWith("Re-sync failed")) {
            return RESYNC_ERROR;
        }
        if (stored.equals("Probe returned null result")) {
            return PROBE_NO_ANSWER;
        }
        if (stored.equals("OK")) {
            return PROBE_OK;
        }
        return null;
    }
}
