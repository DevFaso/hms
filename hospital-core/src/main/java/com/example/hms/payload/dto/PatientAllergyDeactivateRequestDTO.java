package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload used to retire an allergy entry with an audit justification.")
public class PatientAllergyDeactivateRequestDTO {

    @NotBlank(message = "A justification is required when deactivating an allergy entry.")
    @Size(max = 512)
    private String reason;
}
