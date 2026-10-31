package com.example.hms.payload.dto;

import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the atomic triage submission (MVP 2).
 * Captures vitals, chief complaint, acuity, fall risk, and room assignment in a single call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriageSubmissionRequestDTO {

    // ── Vital signs ────────────────────────────────────────────

    @DecimalMin(value = VitalSignLimits.TEMPERATURE_MIN_C, message = "{vitals.temperature.range}")
    @DecimalMax(value = VitalSignLimits.TEMPERATURE_MAX_C, message = "{vitals.temperature.range}")
    private Double temperatureCelsius;

    @Min(20) @Max(300)
    private Integer heartRateBpm;

    @Min(4) @Max(80)
    private Integer respiratoryRateBpm;

    @Min(40) @Max(300)
    private Integer systolicBpMmHg;

    @Min(20) @Max(200)
    private Integer diastolicBpMmHg;

    @Min(0) @Max(100)
    private Integer spo2Percent;

    private Double weightKg;

    private Double heightCm;

    /** Supplemental oxygen at measurement time — NEWS2 parameter (P3 #25b). */
    private Boolean onOxygen;

    /** ACVPU consciousness assessment — NEWS2 parameter (P3 #25b). */
    private com.example.hms.enums.ConsciousnessLevel consciousnessLevel;

    /** Pain scale 0-10 */
    @Min(0) @Max(10)
    private Integer painScale;

    /**
     * QA D1031: the fundal height measured at an antenatal contact, in cm.
     * Charted with the vitals in the clinician's language; no expected value
     * is computed (no reference curve is held in the codebase).
     */
    @jakarta.validation.constraints.Positive
    private Integer fundalHeightCm;

    // ── Clinical assessment ────────────────────────────────────

    @Size(max = 2048)
    private String chiefComplaint;

    /**
     * Emergency Severity Index: 1 (most urgent) to 5 (least urgent).
     * QA D511: mandatory — a triage with no acuity sent the patient to the
     * doctor's queue unranked.
     */
    @jakarta.validation.constraints.NotNull(message = "{triage.esi.required}")
    @Min(1) @Max(5)
    private Integer esiScore;

    private boolean fallRisk;

    /** Numeric fall-risk score (e.g. Morse scale). */
    private Integer fallRiskScore;

    // ── Rooming ────────────────────────────────────────────────

    /** Exam room identifier, e.g. "Room 3A". */
    @Size(max = 100)
    private String roomAssignment;

    @AssertTrue(message = "{vitals.bloodPressure.invalid}")
    @JsonIgnore
    public boolean isBloodPressureOrdered() {
        return VitalSignLimits.bloodPressureOrdered(systolicBpMmHg, diastolicBpMmHg);
    }
}
