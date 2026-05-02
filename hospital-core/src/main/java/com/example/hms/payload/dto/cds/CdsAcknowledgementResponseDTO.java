package com.example.hms.payload.dto.cds;

import com.example.hms.enums.CdsAcknowledgementAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Recorded acknowledgement of a Best-Practice Advisory")
public class CdsAcknowledgementResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String cardUuid;
    private String cardSummary;
    private String indicator;
    private CdsAcknowledgementAction action;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
