package com.example.hms.payload.dto.empi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Admin request to merge two duplicate patients at the identity-graph level (P1 #8). */
@Data
public class EmpiPatientMergeRequestDTO {

    @NotNull(message = "Primary (surviving) patient identifier is required")
    private UUID primaryPatientId;

    @NotNull(message = "Secondary (duplicate) patient identifier is required")
    private UUID secondaryPatientId;

    @Size(max = 2000)
    private String notes;
}
