package com.example.hms.payload.dto;

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
public class PatientVitalSignResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID registrationId;
    private UUID hospitalId;
    private String hospitalName;
    private UUID recordedByStaffId;
    private UUID recordedByAssignmentId;
    private String recordedByName;
    private String source;
    private Double temperatureCelsius;
    private Integer heartRateBpm;
    private Integer respiratoryRateBpm;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer spo2Percent;
    private Integer bloodGlucoseMgDl;
    private Double weightKg;
    private Double heightCm;
    private Double headCircumferenceCm;
    private String bodyPosition;
    private String notes;
    private boolean clinicallySignificant;

    /* ── NEWS2 (P3 #25b) ─────────────────────────────────────────────
       Computed on read from this bundle's parameters. When incomplete,
       the score covers only what was recorded and newsMissing names the
       gaps — consumers must render the incompleteness, never hide it. */
    private Boolean onOxygen;
    private com.example.hms.enums.ConsciousnessLevel consciousnessLevel;
    private Integer newsScore;
    /** LOW | LOW_MEDIUM | MEDIUM | HIGH */
    private String newsRiskBand;
    private Boolean newsComplete;
    private java.util.List<String> newsMissing;

    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
