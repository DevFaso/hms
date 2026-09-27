package com.example.hms.utility;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class MessageUtil {

    /**
     * What a bundle key looks like: dot-separated segments, no whitespace.
     * A sentence always has a space, so it can never be a key.
     */
    private static final Pattern MESSAGE_KEY =
        Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");

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

    /** True when {@code text} has the shape of a bundle key rather than of a sentence. */
    public static boolean isMessageKey(String text) {
        return text != null && MESSAGE_KEY.matcher(text).matches();
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
            String resolved = messageSource.getMessage(text, args, LocaleContextHolder.getLocale());
            return resolved != null ? resolved : text;
        } catch (RuntimeException e) {
            return text;
        }
    }
}
