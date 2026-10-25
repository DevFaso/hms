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

    /** QA D295: the partograph and the labour durations are measured from it. */
    @jakarta.validation.constraints.NotNull(message = "{maternity.labor.onset.required}")
    @com.example.hms.validation.NotInClinicalFuture
    private LocalDateTime laborOnsetAt;
    @com.example.hms.validation.NotInClinicalFuture
    private LocalDateTime admittedAt;
    private MembraneStatus membraneStatus;
    @com.example.hms.validation.NotInClinicalFuture
    private LocalDateTime membraneRuptureAt;

    /**
     * Optional overrides; when null these are snapshotted from the
     * patient's current MaternalHistory version.
     */
    private Integer gestationalAgeWeeks;
    private Integer gravida;
    private Integer para;

    private String riskNotes;

    /**
     * QA D214: required when a birth was recorded for this mother recently;
     * the clinician's reason for opening a new labour episode anyway.
     */
    @jakarta.validation.constraints.Size(max = 500)
    private String recentDeliveryOverrideReason;
}
