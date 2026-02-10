package com.example.hms.service;

import com.example.hms.model.Notification;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    List<Notification> getNotificationsForUser(String username);
    Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable);
    Notification createNotification(String message, String recipientUsername);
    void markAsRead(UUID notificationId);
}
