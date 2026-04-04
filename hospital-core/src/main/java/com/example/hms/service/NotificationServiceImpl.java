package com.example.hms.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.hms.model.Notification;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.controller.NotificationWebSocketController;
import com.example.hms.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationWebSocketController notificationWebSocketController;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final UserRepository userRepository;

    @Override
        public List<Notification> getNotificationsForUser(String username) {
            return notificationRepository.findByRecipientUsername(username, Pageable.unpaged()).getContent();
    }

    @Override
        public Page<Notification> getNotificationsForUser(String username, Boolean read, String search, Pageable pageable) {
            if (read != null && search != null && !search.isEmpty()) {
                return notificationRepository.findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(username, read, search, pageable);
            } else if (read != null) {
                return notificationRepository.findByRecipientUsernameAndRead(username, read, pageable);
            } else if (search != null && !search.isEmpty()) {
                return notificationRepository.findByRecipientUsernameAndMessageContainingIgnoreCase(username, search, pageable);
            } else {
                return notificationRepository.findByRecipientUsername(username, pageable);
            }
    }

    @Override
    public Notification createNotification(String message, String recipientUsername) {
        return createNotification(message, recipientUsername, null);
    }

    @Override
    public Notification createNotification(String message, String recipientUsername, String type) {
        Notification notification = Notification.builder()
                .message(message)
                .recipientUsername(recipientUsername)
                .type(type)
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

    @Override
    public void markAsRead(UUID notificationId, String ownerUsername) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (ownerUsername.equals(n.getRecipientUsername())) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Override
    public long countUnreadForUser(String username) {
        return notificationRepository.countByRecipientUsernameAndReadFalse(username);
    }

    @Override
    @Transactional
    public int markAllReadForUser(String username) {
        return notificationRepository.markAllReadForUser(username);
    }

    // ── Notification preferences ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> getPreferences(UUID userId) {
        return notificationPreferenceRepository.findByUser_Id(userId)
                .stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public List<NotificationPreferenceDTO> updatePreferences(UUID userId, List<NotificationPreferenceUpdateDTO> updates) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        notificationPreferenceRepository.deleteByUser_Id(userId);
        notificationPreferenceRepository.flush();

        List<NotificationPreference> entities = updates.stream().map(u ->
                NotificationPreference.builder()
                        .user(user)
                        .notificationType(u.getNotificationType())
                        .channel(u.getChannel())
                        .enabled(u.isEnabled())
                        .build()
        ).toList();

        return notificationPreferenceRepository.saveAll(entities)
                .stream().map(this::toDTO).toList();
    }

    private NotificationPreferenceDTO toDTO(NotificationPreference p) {
        return NotificationPreferenceDTO.builder()
                .id(p.getId())
                .notificationType(p.getNotificationType())
                .channel(p.getChannel())
                .enabled(p.isEnabled())
                .build();
    }
}
