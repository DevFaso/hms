package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to declare a break-the-glass emergency-access session.")
public class BreakGlassDeclareRequestDTO {

    @NotNull
    @Schema(description = "Patient whose chart will be accessed under this session.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotNull
    @Schema(description = "Hospital under whose authority the override is invoked.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @NotBlank
    @Size(min = 10, max = 1024)
    @Schema(description = "Clinical justification (logged + reviewable). Min 10 characters to discourage no-op declarations.",
            example = "Patient unconscious, no proxy reachable; need allergy/med history for emergency intubation.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @Min(15)
    @Schema(description = "Optional override TTL in minutes (15–240). Defaults to 240 (4h).",
            example = "60")
    private Integer ttlMinutes;
}
