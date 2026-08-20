package com.example.hms.payload.dto.clinical.labor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborEpisodeResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private UUID registrationId;
    private UUID maternalHistoryId;
    private String admittedByStaffName;

    private LocalDateTime laborOnsetAt;
    private LocalDateTime admittedAt;
    private String membraneStatus;
    private LocalDateTime membraneRuptureAt;

    private Integer gestationalAgeWeeks;
    private Integer gravida;
    private Integer para;

    private LocalDateTime activePhaseStartAt;
    private String status;
    private String outcome;
    private String riskNotes;

    private long entryCount;
    private boolean deliveryRecorded;
}
