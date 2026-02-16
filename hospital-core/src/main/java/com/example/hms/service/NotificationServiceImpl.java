package com.example.hms.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.hms.model.Notification;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.controller.NotificationWebSocketController;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationWebSocketController notificationWebSocketController;

    @Override
        public List<Notification> getNotificationsForUser(String username) {
            // For legacy use, return all notifications for user, sorted by createdAt desc
            return notificationRepository.findByRecipientUsername(username, Pageable.unpaged()).getContent();
    }

    @Override
        public Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable) {
            if (read != null && search != null && !search.isEmpty()) {
                // Filter by read and search
                return notificationRepository.findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(username, read, search, pageable);
            } else if (read != null) {
                // Filter by read
                return notificationRepository.findByRecipientUsernameAndRead(username, read, pageable);
            } else if (search != null && !search.isEmpty()) {
                // Filter by search
                return notificationRepository.findByRecipientUsernameAndMessageContainingIgnoreCase(username, search, pageable);
            } else {
                // No filter, just paginate
                return notificationRepository.findByRecipientUsername(username, pageable);
            }
    }

    @Override
    public Notification createNotification(String message, String recipientUsername) {
        Notification notification = Notification.builder()
                .message(message)
                .recipientUsername(recipientUsername)
                .createdAt(LocalDateTime.now())
                .read(false)
                .build();
        Notification saved = notificationRepository.save(notification);
        notificationWebSocketController.sendNotification(saved);
        return saved;
    }

    @Override
    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setRead(true);
            notificationRepository.save(n);
        });
    }
}
