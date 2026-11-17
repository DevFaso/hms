package com.example.hms.service;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Notification;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * A test double for writers of notifications: mocked with
 * {@code CALLS_REAL_METHODS}, a keyed {@link #createNotification} renders the
 * body in French as the real service stores it and hands it to
 * {@link #created(String, String, String)}, which the tests verify. A free
 * text notification goes to {@code created} as written.
 */
public abstract class RenderingNotificationService implements NotificationService {

    private static final MessageSource MESSAGES = TestMessageSources.bundles();
    private static final NotificationLocalizer LOCALIZER = new NotificationLocalizer(MESSAGES);

    /**
     * What a test verifies: the stored (French) body, the recipient and the type.
     * A Mockito verification hook: the parameters ARE the matcher surface, never read here.
     */
    @SuppressWarnings("java:S1172")
    public Notification created(String message, String recipientUsername, String type) {
        return null;
    }

    /** The body as stored for {@code messageKey}, rendered in {@code locale}. */
    public static String render(String messageKey, Object[] args, Locale locale) {
        return LOCALIZER.render(messageKey, NotificationLocalizer.storable(args), locale);
    }

    @Override
    public Notification createNotification(String messageKey, Object[] args, String recipientUsername, String type) {
        String french = NotificationServiceImpl.capped(render(messageKey, args, NotificationLocales.STAFF));
        return created(french, recipientUsername, type);
    }

    @Override
    public Notification createLiteralNotification(String message, String recipientUsername, String type) {
        return created(message, recipientUsername, type);
    }
}
