package com.example.hms.payload.dto.discharge;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.enums.PatientStayStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO exposed to both nurse and doctor dashboards describing a discharge approval.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DischargeApprovalResponseDTO {

    private UUID id;
    private DischargeStatus status;

    private UUID patientId;
    private String patientName;
    private UUID registrationId;

    private UUID hospitalId;
    private String hospitalName;

    private UUID nurseStaffId;
    private String nurseName;
    private UUID nurseAssignmentId;

    private UUID doctorStaffId;
    private String doctorName;
    private UUID doctorAssignmentId;

    private String nurseSummary;
    private String doctorNote;
    private String rejectionReason;

    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime resolvedAt;

    private PatientStayStatus currentStayStatus;
    private LocalDateTime stayStatusUpdatedAt;
}
