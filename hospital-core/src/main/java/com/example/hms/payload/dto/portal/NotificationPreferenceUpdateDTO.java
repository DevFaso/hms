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

    /**
     * QA D1364 (round 31): on or off is said, never assumed. A primitive read
     * a missing or blank value as "off" (a channel silently switched off) and
     * a blank one was reported as malformed, hiding the blank type and channel.
     */
    @NotNull
    private Boolean enabled;
}
