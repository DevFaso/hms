package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /nurse/patients/{patientId}/vitals — inline vital sign capture.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseVitalCaptureRequestDTO {

    private Double temperatureCelsius;
    private Integer heartRateBpm;
    private Integer respiratoryRateBpm;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer spo2Percent;
    private Integer bloodGlucoseMgDl;
    private Double weightKg;
    private String notes;
}
