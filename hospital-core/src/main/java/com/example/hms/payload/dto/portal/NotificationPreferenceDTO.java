package com.example.hms.payload.dto.portal;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreferenceDTO {
    private UUID id;
    private NotificationType notificationType;
    private NotificationChannel channel;
    private boolean enabled;
}
