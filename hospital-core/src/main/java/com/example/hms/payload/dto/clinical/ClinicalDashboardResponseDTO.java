package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for unified Clinical Dashboard Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalDashboardResponseDTO {

    private List<DashboardKPI> kpis;

    private List<ClinicalAlertDTO> alerts;

    private InboxCountsDTO inboxCounts;

    private OnCallStatusDTO onCallStatus;

    private List<RoomedPatientDTO> roomedPatients;
}
