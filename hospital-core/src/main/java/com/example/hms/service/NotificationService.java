package com.example.hms.service;

import com.example.hms.model.Notification;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    List<Notification> getNotificationsForUser(String username);
    Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable);
    Notification createNotification(String message, String recipientUsername);
    Notification createNotification(String message, String recipientUsername, String type);
    void markAsRead(UUID notificationId);
    void markAsRead(UUID notificationId, String ownerUsername);
    long countUnreadForUser(String username);
    int markAllReadForUser(String username);

    // ── Notification preferences ─────────────────────────────────────────
    List<NotificationPreferenceDTO> getPreferences(UUID userId);
    List<NotificationPreferenceDTO> updatePreferences(UUID userId, List<NotificationPreferenceUpdateDTO> updates);
}
