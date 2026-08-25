package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.TransfusionAdministrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransfusionAdministrationResponseDTO {

    private UUID id;
    private UUID requestId;
    private UUID bloodUnitId;
    private String unitNumber;
    private UUID patientId;
    private String patientName;
    private TransfusionAdministrationStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer volumeTransfusedMl;
    private String administeredByName;
    private String verifiedByName;
    private String verificationMethod;
    private String stopReason;
    private String notes;
    private List<TransfusionReactionResponseDTO> reactions;
}
