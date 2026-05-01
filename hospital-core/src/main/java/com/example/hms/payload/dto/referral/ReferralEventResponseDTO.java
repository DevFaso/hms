package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for one row on the referral state-machine audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralEventResponseDTO {

    private UUID id;
    private UUID referralId;
    private ReferralEventType eventType;
    private ReferralStatus fromStatus;
    private ReferralStatus toStatus;
    private String actorUsername;
    private String actorLabel;
    private String note;
    private LocalDateTime recordedAt;
}
