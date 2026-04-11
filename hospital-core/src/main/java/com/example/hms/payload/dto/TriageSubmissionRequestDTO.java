package com.example.hms.payload.dto;

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

    /** Pain scale 0-10 */
    @Min(0) @Max(10)
    private Integer painScale;

    // ── Clinical assessment ────────────────────────────────────

    @Size(max = 2048)
    private String chiefComplaint;

    /** Emergency Severity Index: 1 (most urgent) to 5 (least urgent). */
    @Min(1) @Max(5)
    private Integer esiScore;

    private boolean fallRisk;

    /** Numeric fall-risk score (e.g. Morse scale). */
    private Integer fallRiskScore;

    // ── Rooming ────────────────────────────────────────────────

    /** Exam room identifier, e.g. "Room 3A". */
    @Size(max = 100)
    private String roomAssignment;
}
