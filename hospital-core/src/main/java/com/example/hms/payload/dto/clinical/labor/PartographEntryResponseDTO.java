package com.example.hms.payload.dto.clinical.labor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartographEntryResponseDTO {

    private UUID id;
    private UUID episodeId;
    private UUID patientId;

    private LocalDateTime observationTime;
    private LocalDateTime documentedAt;
    private boolean lateEntry;
    private String recordedByStaffName;

    private Integer fetalHeartRateBpm;
    private String liquorColour;
    private String mouldingDegree;

    private Integer cervicalDilationCm;
    private Integer descentFifths;
    private Integer contractionsPerTenMinutes;
    private Integer contractionDurationSeconds;

    private Integer oxytocinDropsPerMinute;
    private String drugsGiven;
    private String ivFluids;

    private Integer pulseBpm;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Double temperatureCelsius;
    private Integer urineOutputMl;
    private String urineProtein;
    private String urineAcetone;

    private String notes;
    private List<LaborAlertDTO> alerts;

    /**
     * Hours since the episode's active-phase anchor at this observation
     * time; null while still in the latent phase. Lets the client plot
     * against the WHO alert/action lines without re-deriving the anchor.
     */
    private Double hoursSinceActivePhaseStart;
}
