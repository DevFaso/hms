package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceMetadataDTO {

    private String ehrSystem;
    private String billingSystem;
    private String inventorySystem;
    private String integrationNotes;
}
