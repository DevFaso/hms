package com.example.hms.payload.dto.notification;

import jakarta.validation.constraints.NotBlank;

/**
 * An administrator's message to one user, in the body: the recipient's login and the message
 * never go into a URL.
 */
public record AdminNotificationRequestDTO(
    @NotBlank String message,
    @NotBlank String recipientUsername) {
}
