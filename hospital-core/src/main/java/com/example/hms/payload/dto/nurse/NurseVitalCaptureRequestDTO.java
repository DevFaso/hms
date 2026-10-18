package com.example.hms.payload.dto.nurse;

import com.example.hms.payload.dto.VitalSignLimits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /nurse/patients/{patientId}/vitals — inline vital sign capture.
 * Every reading is bounded by {@link VitalSignLimits}: an impossible value is a
 * 400, never a row in the chart.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseVitalCaptureRequestDTO {

    @DecimalMin(value = VitalSignLimits.TEMPERATURE_MIN_C, message = "{vitals.temperature.range}")
    @DecimalMax(value = VitalSignLimits.TEMPERATURE_MAX_C, message = "{vitals.temperature.range}")
    private Double temperatureCelsius;

    @Min(value = VitalSignLimits.HEART_RATE_MIN, message = "{vitals.heartRate.range}")
    @Max(value = VitalSignLimits.HEART_RATE_MAX, message = "{vitals.heartRate.range}")
    private Integer heartRateBpm;

    @Min(value = VitalSignLimits.RESPIRATORY_RATE_MIN, message = "{vitals.respiratoryRate.range}")
    @Max(value = VitalSignLimits.RESPIRATORY_RATE_MAX, message = "{vitals.respiratoryRate.range}")
    private Integer respiratoryRateBpm;

    @Min(value = VitalSignLimits.SYSTOLIC_MIN, message = "{vitals.systolic.range}")
    @Max(value = VitalSignLimits.SYSTOLIC_MAX, message = "{vitals.systolic.range}")
    private Integer systolicBpMmHg;

    @Min(value = VitalSignLimits.DIASTOLIC_MIN, message = "{vitals.diastolic.range}")
    @Max(value = VitalSignLimits.DIASTOLIC_MAX, message = "{vitals.diastolic.range}")
    private Integer diastolicBpMmHg;

    @Min(value = VitalSignLimits.SPO2_MIN, message = "{vitals.spo2.range}")
    @Max(value = VitalSignLimits.SPO2_MAX, message = "{vitals.spo2.range}")
    private Integer spo2Percent;

    @Min(value = VitalSignLimits.GLUCOSE_MIN, message = "{vitals.glucose.range}")
    @Max(value = VitalSignLimits.GLUCOSE_MAX, message = "{vitals.glucose.range}")
    private Integer bloodGlucoseMgDl;

    @DecimalMin(value = VitalSignLimits.WEIGHT_MIN_KG, message = "{vitals.weight.range}")
    @DecimalMax(value = VitalSignLimits.WEIGHT_MAX_KG, message = "{vitals.weight.range}")
    private Double weightKg;

    @DecimalMin(value = VitalSignLimits.HEIGHT_MIN_CM, message = "{vitals.height.range}")
    @DecimalMax(value = VitalSignLimits.HEIGHT_MAX_CM, message = "{vitals.height.range}")
    private Double heightCm;

    @DecimalMin(value = VitalSignLimits.HEAD_CIRCUMFERENCE_MIN_CM, message = "{vitals.headCircumference.range}")
    @DecimalMax(value = VitalSignLimits.HEAD_CIRCUMFERENCE_MAX_CM, message = "{vitals.headCircumference.range}")
    private Double headCircumferenceCm;

    /** Supplemental oxygen at measurement time — NEWS2 parameter (P3 #25b). */
    private Boolean onOxygen;

    /** ACVPU consciousness assessment — NEWS2 parameter (P3 #25b). */
    private com.example.hms.enums.ConsciousnessLevel consciousnessLevel;

    @Size(max = 1000)
    private String notes;
}
