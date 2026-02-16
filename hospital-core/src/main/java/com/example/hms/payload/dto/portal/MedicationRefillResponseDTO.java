package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Medication refill request status")
public class MedicationRefillResponseDTO {

    @Schema(description = "Refill request ID")
    private UUID id;

    @Schema(description = "Prescription ID")
    private UUID prescriptionId;

    @Schema(description = "Medication name")
    private String medicationName;

    @Schema(description = "Patient ID")
    private UUID patientId;

    @Schema(description = "Current status: REQUESTED, APPROVED, DENIED, DISPENSED")
    private String status;

    @Schema(description = "Preferred pharmacy")
    private String preferredPharmacy;

    @Schema(description = "Patient notes")
    private String notes;

    @Schema(description = "Provider response notes")
    private String providerNotes;

    @Schema(description = "When the request was submitted")
    private LocalDateTime requestedAt;

    @Schema(description = "When the request was last updated")
    private LocalDateTime updatedAt;
}
