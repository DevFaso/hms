package com.example.hms.service.i18n;

import com.example.hms.model.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a notification body in the language of whoever reads it (QA D665).
 *
 * <p>A row written with a message key keeps the key and its arguments; this
 * class renders them in the reader's locale at read time. Rows without a key
 * (written before V225, or free text an administrator typed) show their stored
 * text. An argument that is itself translatable text (a fallback such as
 * "patient") is stored as {@code {"key": "..."}} through {@link #key(String)}
 * and rendered in the same locale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationLocalizer {

    private static final String KEY = "key";

    private final MessageSource messageSource;

    /** An argument that is a message key, rendered in the reader's locale. */
    public static Map<String, String> key(String messageKey) {
        return Map.of(KEY, messageKey);
    }

    /** The arguments as stored: numbers and booleans as is, keys as {@code {"key"}}, anything else as text. */
    public static List<Object> storable(Object[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyList();
        }
        List<Object> out = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg == null) {
                out.add("");
            } else if (arg instanceof Number || arg instanceof Boolean) {
                out.add(arg);
            } else if (arg instanceof Map<?, ?> map && map.get(KEY) instanceof String k) {
                out.add(key(k));
            } else {
                out.add(String.valueOf(arg));
            }
        }
        return out;
    }

    public String render(String messageKey, List<Object> args, Locale locale) {
        Object[] resolved = args == null ? new Object[0] : args.stream()
            .map(arg -> arg instanceof Map<?, ?> map && map.get(KEY) instanceof String k
                ? messageSource.getMessage(k, null, k, locale)
                : arg)
            .toArray();
        return messageSource.getMessage(messageKey, resolved, locale);
    }

    /**
     * A copy of {@code notification} with its body in {@code locale}; the row
     * itself when it has no key or the key no longer renders (its stored text
     * is still right, only in French). Never mutates the possibly managed row.
     */
    public Notification localized(Notification notification, Locale locale) {
        if (notification == null || notification.getMessageKey() == null || locale == null) {
            return notification;
        }
        String body;
        try {
            body = render(notification.getMessageKey(), notification.getMessageArgs(), locale);
        } catch (RuntimeException ex) {
            log.warn("Notification {} kept its stored text: key {} did not render ({})",
                notification.getId(), notification.getMessageKey(), ex.getClass().getSimpleName());
            return notification;
        }
        return Notification.builder()
            .id(notification.getId())
            .message(body)
            .messageKey(notification.getMessageKey())
            .messageArgs(notification.getMessageArgs())
            .type(notification.getType())
            .createdAt(notification.getCreatedAt())
            .read(notification.isRead())
            .recipientUsername(notification.getRecipientUsername())
            .build();
    }
}
