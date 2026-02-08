package com.example.hms.payload.dto;

import jakarta.validation.constraints.*;
import lombok.*;

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
    @PastOrPresent(message = "performedAt cannot be in the future")
    private LocalDateTime performedAt;

    @Size(max = 100, message = "Outcome must be at most 100 characters")
    private String outcome;

    @Size(max = 2000, message = "Notes must be at most 2000 characters")
    private String notes;
}
