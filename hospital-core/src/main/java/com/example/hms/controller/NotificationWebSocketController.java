package com.example.hms.controller;

import com.example.hms.model.Notification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class NotificationWebSocketController {
    private final SimpMessagingTemplate messagingTemplate;

    public void sendNotification(Notification notification) {
        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }
}
