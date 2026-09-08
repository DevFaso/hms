package com.example.hms.payload.dto.recordaccess;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecordSharingOptOutDTO(
    UUID patientId,
    boolean inForce,
    LocalDateTime optedOutAt,
    String reason,
    LocalDateTime revokedAt
) {
    public static RecordSharingOptOutDTO none(UUID patientId) {
        return new RecordSharingOptOutDTO(patientId, false, null, null, null);
    }
}
