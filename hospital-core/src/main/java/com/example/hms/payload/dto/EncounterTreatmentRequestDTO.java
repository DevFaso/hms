package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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
public class EncounterTreatmentRequestDTO {

    @NotNull
    private UUID encounterId;

    @NotNull
    private UUID treatmentId;

    /** Optional: require staff if business rules demand it */
    private UUID staffId;

    @NotNull
    @PastOrPresent(message = "{encounterTreatment.performedAt.past}")
    private LocalDateTime performedAt;

    @Size(max = 100, message = "{encounterTreatment.outcome.size}")
    private String outcome;

    @Size(max = 2000, message = "{encounterTreatment.notes.size}")
    private String notes;
}
