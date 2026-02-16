package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@OneOf(fields = {"patientId", "patientIdentifier"}, message = "Provide patientId or patientIdentifier.")
public class PrescriptionRequestDTO {

    private UUID id; // ignored on create

    private UUID patientId;
    private String patientIdentifier; // username/email/MRN fallback

    private UUID staffId;     // prescriber

    private UUID encounterId; // anchor

    @NotBlank
    @Size(max = 255)
    private String medicationName;

    @Size(max = 100)
    private String dosage;

    @Size(max = 100)
    private String frequency;

    @Size(max = 100)
    private String duration;

    @Size(max = 1024)
    private String notes;

    /**
     * Force override allergy checking (for severe allergies).
     * When true, allows prescribing despite documented severe allergies.
     * Should be used only after clinician review.
     */
    private Boolean forceOverride;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
