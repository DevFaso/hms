package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated summary counts powering the Nurse Dashboard header cards.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseDashboardSummaryDTO {

    private long assignedPatients;
    private long vitalsDue;
    private long medicationsDue;
    private long medicationsOverdue;
    private long ordersPending;
    private long handoffsPending;
    private long announcements;
}
