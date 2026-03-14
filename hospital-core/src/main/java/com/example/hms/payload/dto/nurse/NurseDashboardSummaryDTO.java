package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseDashboardSummaryDTO {

    private int patientsAssigned;
    private int vitalsOverdueCount;
    private int medsOverdueCount;
    private int ordersPendingCount;
    private int handoffsCount;
}
