package com.example.hms.controller;

import com.example.hms.model.Notification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class NotificationWebSocketController {
    private final SimpMessagingTemplate messagingTemplate;

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
                notification
            );
        } else {
            // Broadcast (e.g. system-wide announcements) — kept as fallback
            messagingTemplate.convertAndSend("/topic/notifications", notification);
        }
    }
}
