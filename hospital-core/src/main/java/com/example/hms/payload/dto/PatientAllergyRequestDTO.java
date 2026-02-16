package com.example.hms.payload.dto;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload used to capture or modify a structured allergy entry.")
public class PatientAllergyRequestDTO {

    @Schema(description = "Hospital scope for the allergy entry. Defaults to the authenticated hospital if omitted.")
    private UUID hospitalId;

    @NotBlank(message = "Allergen display name is required.")
    @Size(max = 255)
    private String allergenDisplay;

    @Size(max = 64)
    private String allergenCode;

    @Size(max = 100)
    private String category;

    private AllergySeverity severity;

    private AllergyVerificationStatus verificationStatus;

    @Size(max = 255)
    private String reaction;

    @Size(max = 1024)
    private String reactionNotes;

    private LocalDate onsetDate;

    private LocalDate lastOccurrenceDate;

    private LocalDate recordedDate;

    @Size(max = 100)
    private String sourceSystem;

    private Boolean active;
}
