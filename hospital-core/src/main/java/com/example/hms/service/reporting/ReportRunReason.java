package com.example.hms.service.reporting;

import com.example.hms.exception.BusinessException;
import com.example.hms.utility.MessageUtil;
import java.util.Arrays;

/**
 * QA D855: why a report run failed, stored as a message key plus its
 * arguments and put into words only when the run history is read, in the
 * reader's language.
 *
 * <p>The reason used to be {@code ex.getMessage()}, resolved in the locale of
 * the thread that ran the report. A scheduled run has no request, so the
 * scheduler thread fell back to the JVM default and the history kept an
 * English sentence for good, whatever language the admin later read it in.
 *
 * <p>A stored reason is {@value #KEY_PREFIX}{@code key}, followed by each
 * argument after a unit separator. A row written before this form existed is
 * plain text and is shown as written.
 */
public final class ReportRunReason {

    /** Marks a stored reason that is a key, not a sentence. */
    static final String KEY_PREFIX = "i18n:";

    /** Between the key and each argument; never typed in an address or a period token. */
    private static final String ARG_SEPARATOR = "\u001F";

    /** The width of {@code platform.report_runs.error_message}. */
    static final int MAX_LENGTH = 1000;

    /** The reason for a failure that carries no translatable key of its own. */
    public static final String GENERIC_FAILURE_KEY = "reportRun.failed";

    private ReportRunReason() {
    }

    /**
     * The reason to store for {@code ex}: its key and arguments when it is a
     * refusal with a bundle key, the generic failure key otherwise. The text
     * of an unexpected exception is technical, in English, and may quote a
     * recipient address, so it is not stored.
     */
    public static String toStored(RuntimeException ex) {
        if (ex instanceof BusinessException business && MessageUtil.isMessageKey(business.getMessageKey())) {
            StringBuilder stored = new StringBuilder(KEY_PREFIX).append(business.getMessageKey());
            for (Object arg : business.getArgs()) {
                stored.append(ARG_SEPARATOR)
                    .append(String.valueOf(arg).replace(ARG_SEPARATOR, ""));
            }
            if (stored.length() <= MAX_LENGTH) {
                return stored.toString();
            }
            String keyOnly = KEY_PREFIX + business.getMessageKey();
            return keyOnly.length() <= MAX_LENGTH ? keyOnly : KEY_PREFIX + GENERIC_FAILURE_KEY;
        }
        return KEY_PREFIX + GENERIC_FAILURE_KEY;
    }

    /** The key of a stored reason, for a log line; never its arguments. */
    public static String keyOf(String stored) {
        if (stored == null || !stored.startsWith(KEY_PREFIX)) {
            return null;
        }
        String body = stored.substring(KEY_PREFIX.length());
        int separator = body.indexOf(ARG_SEPARATOR);
        return separator < 0 ? body : body.substring(0, separator);
    }

    /**
     * A stored reason in the reader's language (the request's, through
     * {@link MessageUtil#resolveOrRaw}). Plain text written before the key
     * form existed is returned unchanged.
     */
    public static String resolve(String stored) {
        String key = keyOf(stored);
        if (key == null) {
            return stored;
        }
        String[] parts = stored.substring(KEY_PREFIX.length()).split(ARG_SEPARATOR, -1);
        Object[] args = Arrays.copyOfRange(parts, 1, parts.length, Object[].class);
        return MessageUtil.resolveOrRaw(key, args);
    }
}
