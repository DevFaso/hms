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

    /** Type of biological specimen (e.g. Blood, Urine, Tissue). */
    private String specimenType;

    /** Optional physical collection site / container label. */
    private String currentLocation;

    /** Timestamp when the specimen was collected. Defaults to now if absent. */
    private LocalDateTime collectedAt;

    /** Additional free-text notes. */
    private String notes;
}
