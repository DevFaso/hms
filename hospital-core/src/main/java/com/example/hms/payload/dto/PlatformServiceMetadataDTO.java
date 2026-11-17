package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Sizes mirror the embedded {@code PlatformServiceMetadata} columns. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceMetadataDTO {

    @Size(max = 120)
    private String ehrSystem;

    @Size(max = 120)
    private String billingSystem;

    @Size(max = 120)
    private String inventorySystem;

    @Size(max = 255)
    private String integrationNotes;
}
