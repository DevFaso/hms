package com.example.hms.payload.dto.discharge;

import com.example.hms.enums.DischargeDisposition;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating or updating discharge summaries
 * Part of Story #14: Discharge Summary Assembly
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DischargeSummaryRequestDTO {

    @NotNull(message = "{dischargeSummary.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{dischargeSummary.encounterId.required}")
    private UUID encounterId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @NotNull(message = "{dischargeSummary.dischargingProviderId.required}")
    private UUID dischargingProviderId;

    @NotNull(message = "{dischargeSummary.assignmentId.required}")
    private UUID assignmentId;

    // Link to approval if one exists
    private UUID approvalRecordId;

    @NotNull(message = "{dischargeSummary.dischargeDate.required}")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dischargeDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dischargeTime;

    @NotNull(message = "{dischargeSummary.disposition.required}")
    private DischargeDisposition disposition;

    @NotBlank(message = "{dischargeSummary.dischargeDiagnosis.required}")
    @Size(max = 5000, message = "{dischargeSummary.dischargeDiagnosis.size}")
    private String dischargeDiagnosis;

    @Size(max = 5000, message = "{dischargeSummary.hospitalCourse.size}")
    private String hospitalCourse;

    @Size(max = 2000, message = "{dischargeSummary.dischargeCondition.size}")
    private String dischargeCondition;

    // Structured instructions
    @Size(max = 3000)
    private String activityRestrictions;

    @Size(max = 3000)
    private String dietInstructions;

    @Size(max = 3000)
    private String woundCareInstructions;

    @Size(max = 3000)
    private String followUpInstructions;

    @Size(max = 2000)
    private String warningSigns;

    @Size(max = 2000)
    private String patientEducationProvided;

    // Collections
    @Builder.Default
    private List<MedicationReconciliationDTO> medicationReconciliation = new ArrayList<>();

    @Builder.Default
    private List<PendingTestResultDTO> pendingTestResults = new ArrayList<>();

    @Builder.Default
    private List<FollowUpAppointmentDTO> followUpAppointments = new ArrayList<>();

    @Builder.Default
    private List<String> equipmentAndSupplies = new ArrayList<>();

    @Size(max = 2000)
    private String additionalNotes;

    private String patientOrCaregiverSignature;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime signatureDateTime;
}
