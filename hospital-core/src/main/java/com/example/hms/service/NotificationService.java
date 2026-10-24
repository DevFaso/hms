package com.example.hms.service;

import com.example.hms.model.Notification;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    List<Notification> getNotificationsForUser(String username);
    Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable);
    /**
     * Writes a notification whose body is the message-bundle {@code messageKey}
     * with {@code args} (QA D665). The key and arguments are stored, and every
     * reader gets the body in their own language; the French rendering is
     * stored as the message too, for clients that read it as is and for search.
     * An argument that is itself translatable goes in as
     * {@link com.example.hms.service.i18n.NotificationLocalizer#key(String)}.
     */
    Notification createNotification(String messageKey, Object[] args, String recipientUsername, String type);

    /**
     * {@link #createNotification(String, Object[], String, String)} for a
     * notification about one record ({@code subjectId}, QA D726), which
     * {@link #markReadForSubject} can later mark read.
     */
    default Notification createNotification(String messageKey, Object[] args, String recipientUsername, String type,
                                            java.util.UUID subjectId) {
        Notification created = createNotification(messageKey, args, recipientUsername, type);
        if (created != null) {
            created.setSubjectId(subjectId);
        }
        return created;
    }

    /**
     * QA D726: marks read every unread notification of {@code types} about
     * {@code subjectId} (the record was resolved, so the alert is answered).
     * Returns how many were marked.
     */
    int markReadForSubject(java.util.UUID subjectId, java.util.Collection<String> types);

    /**
     * Free text somebody TYPED (an administrator's message, an announcement, a
     * chat line): there is nothing to translate, so it is stored and shown as
     * written. Never for text the system composes: that goes through
     * {@link #createNotification(String, Object[], String, String)}.
     */
    Notification createLiteralNotification(String message, String recipientUsername, String type);

    /** {@code notification} with its body in {@code locale} (a copy; the stored text when it has no key). */
    Notification localize(Notification notification, java.util.Locale locale);
    /** What {@link #markAsRead} did. */
    enum ReadOutcome {
        /** The caller's own notification, or a broadcast the caller may mark, is now read. */
        MARKED,
        /** A broadcast this caller may not mark (its one read flag is everyone's): left as it is. */
        BROADCAST_LEFT_UNREAD,
        /** No such notification, or one addressed to someone else: the caller cannot tell the two apart. */
        NOT_FOUND
    }

    /**
     * Marks a notification read for {@code caller}, with one read of the row.
     * The caller's OWN notification is marked. A broadcast (no recipient) has
     * ONE read flag, shared by everyone: it is marked only when
     * {@code broadcastsMayBeMarked} (the staff endpoint) AND the caller's live
     * context, resolved from the principal as the context filters resolve it
     * (never the request's holder), is staff that is not confined to a
     * provider facility; otherwise it is left as it is. There is deliberately
     * no overload without a caller.
     */
    ReadOutcome markAsRead(UUID notificationId, Principal caller, boolean broadcastsMayBeMarked);
    long countUnreadForUser(String username);
    int markAllReadForUser(String username);

    // ── Notification preferences ─────────────────────────────────────────
    List<NotificationPreferenceDTO> getPreferences(UUID userId);
    List<NotificationPreferenceDTO> updatePreferences(UUID userId, List<NotificationPreferenceUpdateDTO> updates);
}
