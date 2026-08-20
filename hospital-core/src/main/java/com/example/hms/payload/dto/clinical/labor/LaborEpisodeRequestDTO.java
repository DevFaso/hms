package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.enums.MembraneStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** Payload for admitting a patient in labor (starting an episode). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaborEpisodeRequestDTO {

    private UUID hospitalId;
    private UUID registrationId;
    private UUID admittedByStaffId;

    private LocalDateTime laborOnsetAt;
    private LocalDateTime admittedAt;
    private MembraneStatus membraneStatus;
    private LocalDateTime membraneRuptureAt;

    /**
     * Optional overrides; when null these are snapshotted from the
     * patient's current MaternalHistory version.
     */
    private Integer gestationalAgeWeeks;
    private Integer gravida;
    private Integer para;

    private String riskNotes;
}
