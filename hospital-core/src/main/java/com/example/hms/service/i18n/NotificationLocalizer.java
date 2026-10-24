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
    private static final String ARGS = "args";

    private final MessageSource messageSource;

    /** An argument that is a message key, rendered in the reader's locale. */
    public static Map<String, String> key(String messageKey) {
        return Map.of(KEY, messageKey);
    }

    /**
     * An argument that is a message key with its own arguments (QA D665: an
     * obstetric alert sentence inside the notice), rendered in the reader's locale.
     */
    public static Map<String, Object> key(String messageKey, Object[] args) {
        if (args == null) {
            return Map.of(KEY, messageKey);
        }
        return Map.of(KEY, messageKey, ARGS, storable(args));
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
                out.add(map.get(ARGS) instanceof List<?> nested ? key(k, nested.toArray()) : key(k));
            } else {
                out.add(String.valueOf(arg));
            }
        }
        return out;
    }

    public String render(String messageKey, List<Object> args, Locale locale) {
        return messageSource.getMessage(messageKey, resolve(args, locale, 0), locale);
    }

    /** Key arguments rendered in {@code locale}; nested ones at most two levels deep. */
    private Object[] resolve(List<?> args, Locale locale, int depth) {
        if (args == null) {
            return new Object[0];
        }
        return args.stream()
            .map(arg -> {
                if (arg instanceof Map<?, ?> map && map.get(KEY) instanceof String k) {
                    Object[] nested = depth < 2 && map.get(ARGS) instanceof List<?> list
                        ? resolve(list, locale, depth + 1) : null;
                    return messageSource.getMessage(k, nested, k, locale);
                }
                return arg;
            })
            .toArray();
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
