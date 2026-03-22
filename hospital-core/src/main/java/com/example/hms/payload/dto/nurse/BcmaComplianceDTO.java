package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BCMA compliance summary for a hospital (MVP3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BcmaComplianceDTO {

    private long totalAdministrations;
    private long scannedAdministrations;
    private double compliancePercent;
    private long missedScans;
    private long overriddenScans;
}
