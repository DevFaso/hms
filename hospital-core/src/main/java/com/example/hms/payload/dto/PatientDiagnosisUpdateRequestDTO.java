package com.example.hms.payload.dto;

import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Partial update payload for an existing diagnosis/problem entry.")
public class PatientDiagnosisUpdateRequestDTO {

    @Schema(description = "Hospital context override (defaults to authenticated scope)")
    private UUID hospitalId;

    @Schema(description = "Updated display text for the diagnosis")
    private String problemDisplay;

    @Schema(description = "Updated primary code")
    private String problemCode;

    @Schema(description = "Updated ICD version")
    private String icdVersion;

    @Schema(description = "Updated status for the diagnosis")
    private ProblemStatus status;

    @Schema(description = "Toggle chronic designation")
    private Boolean chronic;

    @Schema(description = "Updated severity for the diagnosis")
    private ProblemSeverity severity;

    @Schema(description = "Updated onset date")
    private LocalDate onsetDate;

    @Schema(description = "Updated resolved date (auto-set when marking resolved)")
    private LocalDate resolvedDate;

    @Schema(description = "Updated supporting clinical evidence")
    @Size(max = 4096)
    private String supportingEvidence;

    @Builder.Default
    @Schema(description = "Replacement list of additional coding references")
    private List<String> diagnosisCodes = new ArrayList<>();

    @Schema(description = "Updated clinician notes")
    @Size(max = 2048)
    private String notes;

    @Schema(description = "Reason for the status or chronicity update")
    @Size(max = 500)
    private String changeReason;
}
