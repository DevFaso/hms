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
    /**
     * QA r73 D3700: who recorded the choice, as this reader may know it: PATIENT, STAFF or OTHER_FACILITY
     * ({@code ConsentRecorderLabels.Kind}); null for a legacy, unattributed choice. Never the recorder's account id.
     */
    private String recordedByKind;
    /**
     * Public display name only, and only for a recorder this reader may name (ConsentRecorderLabels); never the
     * account's id, username, address or phone.
     */
    private String recordedByName;
    private java.time.LocalDateTime recordedAt;
}
