package com.example.hms.payload.dto;

import com.example.hms.enums.DischargeDisposition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for discharging a patient
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionDischargeRequestDTO {

    @NotNull(message = "{admissionDischarge.dischargeDisposition.required}")
    private DischargeDisposition dischargeDisposition;

    @NotBlank(message = "{admissionDischarge.dischargeSummary.required}")
    @jakarta.validation.constraints.Size(max = 5000, message = "{admissionDischarge.dischargeSummary.size}")
    private String dischargeSummary;

    @jakarta.validation.constraints.Size(max = 2000, message = "{admissionDischarge.dischargeInstructions.size}")

    private String dischargeInstructions;

    /**
     * QA D218: the diagnosis at discharge, carried onto the stay's encounter so
     * the discharge summary states it instead of "not specified".
     */
    @jakarta.validation.constraints.Size(max = 2000, message = "{admissionDischarge.dischargeDiagnosis.size}")
    // QA D766: a discharge with no diagnosis left the after-visit summary with none.
    // QA D1596: the service requires it only when no discharge summary is written
    // for the stay; a written summary is the one source of the diagnosis.
    private String dischargeDiagnosis;

    @NotNull(message = "{admissionDischarge.dischargingProviderId.required}")
    private UUID dischargingProviderId;

    /**
     * Follow-up appointments to schedule
     */
    private List<Map<String, Object>> followUpAppointments;
}
