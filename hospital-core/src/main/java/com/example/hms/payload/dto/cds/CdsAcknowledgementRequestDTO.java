package com.example.hms.payload.dto.cds;

import com.example.hms.enums.CdsAcknowledgementAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Clinician acknowledgement of a Best-Practice Advisory card")
public class CdsAcknowledgementRequestDTO {

    @NotNull
    @Schema(description = "Patient the advisory was rendered against",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @Schema(description = "Hospital scope (resolved from JWT when omitted)")
    private UUID hospitalId;

    @Size(max = 64)
    @Schema(description = "Stable card UUID emitted by the rule engine, when present")
    private String cardUuid;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Summary text of the card that was acknowledged",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String cardSummary;

    @NotBlank
    @Size(max = 20)
    @Schema(description = "Indicator of the acknowledged card (info | warning | critical)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String indicator;

    @NotNull
    @Schema(description = "ACKNOWLEDGED for routine dismissal, OVERRIDDEN for clinical override of a critical card",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private CdsAcknowledgementAction action;

    @Size(max = 1000)
    @Schema(description = "Optional clinical justification — required by the UI for OVERRIDDEN")
    private String reason;
}
