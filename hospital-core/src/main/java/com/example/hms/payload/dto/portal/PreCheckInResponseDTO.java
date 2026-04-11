package com.example.hms.payload.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO returned after a successful patient pre-check-in.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreCheckInResponseDTO {

    private UUID appointmentId;
    private String appointmentStatus;
    private Boolean preCheckedIn;
    private LocalDateTime preCheckinTimestamp;
    private int questionnaireResponsesSubmitted;
    private Boolean demographicsUpdated;
}
