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
    Notification createNotification(String message, String recipientUsername);
    Notification createNotification(String message, String recipientUsername, String type);
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
