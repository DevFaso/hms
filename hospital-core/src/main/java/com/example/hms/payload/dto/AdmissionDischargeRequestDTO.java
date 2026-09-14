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
    private String dischargeSummary;

    private String dischargeInstructions;

    @NotNull(message = "{admissionDischarge.dischargingProviderId.required}")
    private UUID dischargingProviderId;

    /**
     * Follow-up appointments to schedule
     */
    private List<Map<String, Object>> followUpAppointments;
}
