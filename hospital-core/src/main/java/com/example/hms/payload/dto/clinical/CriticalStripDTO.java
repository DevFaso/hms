package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for the physician cockpit critical-strip — 6 actionable counts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalStripDTO {

    private int criticalLabsCount;

    private int waitingLongCount;

    private int pendingConsultsCount;

    private int unsignedNotesCount;

    private int pendingOrderReviewCount;

    private int activeSafetyAlertsCount;
}
