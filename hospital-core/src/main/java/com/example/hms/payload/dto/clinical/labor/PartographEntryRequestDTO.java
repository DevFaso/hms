package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.payload.dto.VitalSignLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
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

    @com.example.hms.validation.NotInClinicalFuture

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

    // QA D1424: the same bounds as every other vitals form (VitalSignLimits); 70/120
    // used to be stored and to raise "severe hypertension".
    @Min(value = VitalSignLimits.HEART_RATE_MIN, message = "{vitals.heartRate.range}")
    @Max(value = VitalSignLimits.HEART_RATE_MAX, message = "{vitals.heartRate.range}")
    private Integer pulseBpm;
    @Min(value = VitalSignLimits.SYSTOLIC_MIN, message = "{vitals.systolic.range}")
    @Max(value = VitalSignLimits.SYSTOLIC_MAX, message = "{vitals.systolic.range}")
    private Integer systolicBpMmHg;
    @Min(value = VitalSignLimits.DIASTOLIC_MIN, message = "{vitals.diastolic.range}")
    @Max(value = VitalSignLimits.DIASTOLIC_MAX, message = "{vitals.diastolic.range}")
    private Integer diastolicBpMmHg;
    @DecimalMin(value = VitalSignLimits.TEMPERATURE_MIN_C, message = "{vitals.temperature.range}")
    @DecimalMax(value = VitalSignLimits.TEMPERATURE_MAX_C, message = "{vitals.temperature.range}")
    private Double temperatureCelsius;
    @Min(0)
    private Integer urineOutputMl;
    private String urineProtein;
    private String urineAcetone;

    private String notes;

    /** QA D1424 (D111): a diastolic at or above the systolic is a swapped entry. */
    @com.example.hms.validation.ReportedOn("diastolicBpMmHg")
    @jakarta.validation.constraints.AssertTrue(message = "{vitals.bloodPressure.invalid}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBloodPressureOrdered() {
        return VitalSignLimits.bloodPressureOrdered(systolicBpMmHg, diastolicBpMmHg);
    }
}
