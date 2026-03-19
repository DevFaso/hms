package com.example.hms.payload.dto.portal;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreferenceUpdateDTO {
    @NotNull
    private NotificationType notificationType;

    @NotNull
    private NotificationChannel channel;

    private boolean enabled;
}
