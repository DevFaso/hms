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
    /** QA D1402: the as-needed orders on the eMAR, which the two counts above leave out. */
    private long medicationsAsNeeded;
    private long ordersPending;
    private long handoffsPending;
    private long announcements;
}
