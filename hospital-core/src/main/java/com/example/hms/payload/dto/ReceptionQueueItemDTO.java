package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceptionQueueItemDTO {
    private UUID appointmentId;
    private UUID patientId;
    private String patientName;
    private String mrn;
    private LocalDate dateOfBirth;
    /** HH:mm formatted appointment start time */
    private String appointmentTime;
    private String providerName;
    private String departmentName;
    private String appointmentReason;
    /**
     * Computed front-desk status:
     * SCHEDULED | CONFIRMED | PENDING | ARRIVED | IN_PROGRESS | NO_SHOW | COMPLETED | WALK_IN
     */
    private String status;
    private int waitMinutes;
    private UUID encounterId;
    private boolean hasInsuranceIssue;
    private boolean hasOutstandingBalance;
}
