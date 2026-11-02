package com.example.hms.service.i18n;

import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Map;

/**
 * A notification body as a message key and its arguments, not yet rendered
 * (QA D665): the bell stores the key and renders it in each reader's
 * language, while a channel that sends text once (SMS) renders it here. An
 * argument made with {@link NotificationLocalizer#key(String)} is rendered in
 * the same locale.
 */
public record NotificationText(String key, Object[] args) {

    public static NotificationText of(String key, Object... args) {
        return new NotificationText(key, args);
    }

    public String render(MessageSource messageSource, Locale locale) {
        Object[] resolved = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < resolved.length; i++) {
            Object arg = args[i];
            String labValue = NotificationLocalizer.renderLabValue(arg, locale);
            if (labValue != null) {
                // QA D1207: a laboratory value in the locale this text is rendered in.
                resolved[i] = labValue;
            } else {
                resolved[i] = arg instanceof Map<?, ?> map && map.get("key") instanceof String k
                    ? messageSource.getMessage(k, null, k, locale)
                    : arg;
            }
        }
        return messageSource.getMessage(key, resolved, locale);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NotificationText(String otherKey, Object[] otherArgs)
            && java.util.Objects.equals(key, otherKey)
            && java.util.Arrays.equals(args, otherArgs);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hashCode(key) + java.util.Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return "NotificationText[" + key + ", " + java.util.Arrays.toString(args) + "]";
    }
}
