package com.example.hms.payload.dto;

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
public class LabSpecimenRequestDTO {

    /** ID of the lab order this specimen belongs to. */
    private UUID labOrderId;

    /**
     * Type of biological specimen (e.g. Blood, Urine, Tissue). QA D1050: the
     * column is VARCHAR(50); a longer type was refused by the database with a
     * generic error and the collection was lost.
     */
    @jakarta.validation.constraints.Size(max = 50, message = "{lab.specimen.typeTooLong}")
    private String specimenType;

    /** Optional physical collection site / container label (VARCHAR(100)). */
    @jakarta.validation.constraints.Size(max = 100, message = "{lab.specimen.locationTooLong}")
    private String currentLocation;

    /** Timestamp when the specimen was collected. Defaults to now if absent. */
    private LocalDateTime collectedAt;

    /** Additional free-text notes (VARCHAR(2048)). */
    @jakarta.validation.constraints.Size(max = 2048, message = "{lab.specimen.notesTooLong}")
    private String notes;
}
