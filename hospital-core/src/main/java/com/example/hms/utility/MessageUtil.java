package com.example.hms.utility;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class MessageUtil {

    private static MessageSource messageSource;

    @SuppressWarnings({"java:S1118", "java:S3010"})
    public MessageUtil(MessageSource messageSource) {
        MessageUtil.messageSource = messageSource;
    }

    public static void setMessageSource(MessageSource ms) {
        messageSource = ms;
    }

    public static String resolve(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (RuntimeException e) {
            // Graceful fallback
            return "[Missing translation] " + key + (args.length > 0 ? " - " + args[0] : "");
        }
    }

    /**
     * True when {@code text} has the shape of a bundle key rather than of a
     * sentence: two or more dot-separated segments of letters, digits, '_' or
     * '-', starting with a letter, no whitespace. A sentence always has a
     * space, so it can never be a key. Scanned by hand rather than with a
     * regex, whose nested repetition backtracks on long input (Sonar S5998).
     */
    public static boolean isMessageKey(String text) {
        if (text == null || text.isEmpty() || !isAsciiLetter(text.charAt(0))) {
            return false;
        }
        int segments = 1;
        boolean segmentHasChars = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (!segmentHasChars) {
                    return false;
                }
                segments++;
                segmentHasChars = false;
            } else if (isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                segmentHasChars = true;
            } else {
                return false;
            }
        }
        return segments >= 2 && segmentHasChars;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Resolves {@code text} as a message key in the caller's locale (the
     * request's {@code Accept-Language}, via {@link LocaleContextHolder}) when
     * it is one, and returns it unchanged otherwise.
     *
     * <p>Unlike {@link #resolve}, an unknown key is not decorated: this is for
     * exceptions that have always carried free text as well as keys, where a
     * key no bundle knows is still better shown as written than behind a
     * "[Missing translation]" marker. Free text is never looked up at all.
     */
    public static String resolveOrRaw(String text, Object... args) {
        if (!isMessageKey(text) || messageSource == null) {
            return text;
        }
        try {
            return messageSource.getMessage(text, args, LocaleContextHolder.getLocale());
        } catch (RuntimeException e) {
            return text;
        }
    }
}
