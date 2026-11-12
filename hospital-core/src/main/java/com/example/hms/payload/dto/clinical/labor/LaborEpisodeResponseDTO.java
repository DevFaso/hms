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
    /** QA D1631: the hospital that owns the record, so another hospital shows it as theirs, read only. */
    private String hospitalName;
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

    /* Labour transfer between hospitals (provisional rule, 2026-11-12). */
    /** On a closed episode (TRANSFERRED_OUT, LEFT_WITHOUT_HANDOVER): when, why, and where she went. */
    private LocalDateTime transferredAt;
    private String transferReason;
    private UUID transferHospitalId;
    private String transferHospitalName;
    /** On the episode that took over: the episode it continues, and its hospital. */
    private UUID transferredFromEpisodeId;
    private String transferredFromHospitalName;
}
