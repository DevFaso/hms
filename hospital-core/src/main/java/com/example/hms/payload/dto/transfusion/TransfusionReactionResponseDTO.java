package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.TransfusionReactionSeverity;
import com.example.hms.enums.TransfusionReactionType;
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
public class TransfusionReactionResponseDTO {

    private UUID id;
    private UUID administrationId;
    private UUID patientId;
    private String patientName;
    private TransfusionReactionType reactionType;
    private TransfusionReactionSeverity severity;
    private LocalDateTime onsetAt;
    private String signsSymptoms;
    private String actionsTaken;
    private Boolean unitReturnedToLab;
    private String reportedByName;
    private LocalDateTime reportedAt;
    /** Severe or type-defined-severe: drives the escalation banner. */
    private Boolean severe;
}
