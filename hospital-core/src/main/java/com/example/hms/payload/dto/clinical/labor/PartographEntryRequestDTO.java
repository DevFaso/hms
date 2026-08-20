package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.enums.LiquorColour;
import com.example.hms.enums.MouldingDegree;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One partograph timepoint. All observation fields optional — chart what was measured. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartographEntryRequestDTO {

    private UUID hospitalId;
    private UUID recordedByStaffId;

    private LocalDateTime observationTime;
    private Boolean lateEntry;

    @Min(0) @Max(300)
    private Integer fetalHeartRateBpm;
    private LiquorColour liquorColour;
    private MouldingDegree mouldingDegree;

    @Min(0) @Max(10)
    private Integer cervicalDilationCm;
    @Min(0) @Max(5)
    private Integer descentFifths;
    @Min(0) @Max(10)
    private Integer contractionsPerTenMinutes;
    @Min(0) @Max(600)
    private Integer contractionDurationSeconds;

    @Min(0) @Max(120)
    private Integer oxytocinDropsPerMinute;
    private String drugsGiven;
    private String ivFluids;

    @Min(0) @Max(300)
    private Integer pulseBpm;
    @Min(0) @Max(350)
    private Integer systolicBpMmHg;
    @Min(0) @Max(250)
    private Integer diastolicBpMmHg;
    private Double temperatureCelsius;
    @Min(0)
    private Integer urineOutputMl;
    private String urineProtein;
    private String urineAcetone;

    private String notes;
}
