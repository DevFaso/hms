package com.example.hms.controller;

import com.example.hms.model.Notification;
import com.example.hms.security.StompLocaleRegistry;
import com.example.hms.service.i18n.NotificationLocalizer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class NotificationWebSocketController {
    private final SimpMessagingTemplate messagingTemplate;
    private final StompLocaleRegistry localeRegistry;
    private final NotificationLocalizer localizer;

    /**
     * Sends a notification to the specific recipient via their user-scoped
     * WebSocket destination (/user/{username}/topic/notifications).
     * If no recipientUsername is set, falls back to the global topic (broadcast).
     */
    public void sendNotification(Notification notification) {
        if (notification.getRecipientUsername() != null && !notification.getRecipientUsername().isBlank()) {
            messagingTemplate.convertAndSendToUser(
                notification.getRecipientUsername(),
                "/topic/notifications",
                // In the language the recipient chose when they connected (QA D665).
                localeRegistry.localeOf(notification.getRecipientUsername())
                    .map(locale -> localizer.localized(notification, locale))
                    .orElse(notification)
            );
        } else {
            // Broadcast (e.g. system-wide announcements) — kept as fallback
            messagingTemplate.convertAndSend("/topic/notifications", notification);
        }
    }
}
