package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Simplified medication entry for patient dashboards.")
public class PatientMedicationResponseDTO {

    private UUID id;
    private String medicationName;
    private String dosage;
    private String frequency;
    private String route;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String prescribedBy;
    private String indication;
    private String instructions;

    // ── Refills ──────────────────────────────────────────────────────
    // `refillsAllowed` and `refillsRemaining` have been on the prescriptions
    // table since V1 with no consumer anywhere — the patient portal even
    // shipped MEDICATIONS.REFILLS_REMAINING / REFILLS_COUNT translations with
    // no data behind them. This is that data.

    @Schema(description = "Refills the prescriber authorized when writing the prescription.")
    private Integer refillsAllowed;

    @Schema(description = "Refills still available under that original authorization.")
    private Integer refillsRemaining;

    @Schema(description = "Fills already released by an approved refill request.")
    private Integer refillsUsed;

    @Schema(description = "Whether this prescription can still be refilled at all. "
            + "False once it is cancelled, discontinued, or was never signed.")
    private boolean refillable;

    @Schema(description = "Where the patient's most recent refill request stands: "
            + "REQUESTED, PAUSED, APPROVED, DENIED, DISPENSED or CANCELLED. "
            + "Null when they have never asked for one.")
    private String refillRequestStatus;

    @Schema(description = "When that request was last acted on.")
    private LocalDateTime refillRequestUpdatedAt;

    @Schema(description = "The provider's note on that decision — the reason for a hold or denial.")
    private String refillProviderNotes;

    @Schema(description = "True while a request is awaiting a decision (REQUESTED or PAUSED), "
            + "so the portal can suppress a duplicate request the backend would reject.")
    private boolean refillRequestOpen;
}
