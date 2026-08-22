package com.example.hms.payload.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
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
public class PatientVitalSignRequestDTO {

    private UUID registrationId;
    private UUID hospitalId;
    private UUID recordedByStaffId;
    private UUID recordedByAssignmentId;

    @Size(max = 40)
    private String source;

    private Double temperatureCelsius;

    @Min(20)
    @Max(300)
    private Integer heartRateBpm;

    @Min(4)
    @Max(80)
    private Integer respiratoryRateBpm;

    @Min(40)
    @Max(300)
    private Integer systolicBpMmHg;

    @Min(20)
    @Max(200)
    private Integer diastolicBpMmHg;

    @Min(0)
    @Max(100)
    private Integer spo2Percent;

    @Min(20)
    @Max(800)
    private Integer bloodGlucoseMgDl;

    // Floor at 0.2 kg, not 1.0 — sub-1kg NICU neonates are real patients and the
    // old floor rejected their weights outright (growth charts start at birth).
    @DecimalMin(value = "0.2")
    @DecimalMax(value = "400.0")
    private Double weightKg;

    @DecimalMin(value = "20.0")
    @DecimalMax(value = "250.0")
    private Double heightCm;

    @DecimalMin(value = "15.0")
    @DecimalMax(value = "70.0")
    private Double headCircumferenceCm;

    @Size(max = 40)
    private String bodyPosition;

    @Size(max = 1000)
    private String notes;

    private Boolean clinicallySignificant;

    @PastOrPresent
    private LocalDateTime recordedAt;
}
