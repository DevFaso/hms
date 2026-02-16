package com.example.hms.payload.dto;

import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Request payload for creating a new patient diagnosis entry.")
public class PatientDiagnosisRequestDTO {

    @NotNull
    @Schema(description = "Hospital context for the diagnosis.")
    private UUID hospitalId;

    @NotBlank
    @Schema(description = "Human readable name for the diagnosis/problem.")
    private String problemDisplay;

    @Schema(description = "Primary coded value (ICD-10, SNOMED, etc.).")
    private String problemCode;

    @Schema(description = "ICD version associated with the primary code.")
    private String icdVersion;

    @Schema(description = "Clinical status of the diagnosis.")
    private ProblemStatus status;

    @Schema(description = "Severity classification for the diagnosis.")
    private ProblemSeverity severity;

    @Schema(description = "Date when the diagnosis started or was first observed.")
    private LocalDate onsetDate;

    @Schema(description = "Supporting narrative or clinical evidence for the diagnosis")
    @Size(max = 4096)
    private String supportingEvidence;

    @Builder.Default
    @Schema(description = "Additional coding references attached to the diagnosis")
    private List<String> diagnosisCodes = new ArrayList<>();

    @Schema(description = "Free-form notes recorded by the clinician")
    @Size(max = 2048)
    private String notes;

    @Schema(description = "Marks the diagnosis as chronic if true")
    private Boolean chronic;

    @Schema(description = "Source system if the diagnosis was imported")
    private String sourceSystem;
}
