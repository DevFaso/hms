package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for removing a diagnosis entry with justification")
public class PatientDiagnosisDeleteRequestDTO {

    @NotBlank
    @Schema(description = "Justification required to remove a diagnosis from the active list")
    private String reason;
}
