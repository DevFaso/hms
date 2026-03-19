package com.example.hms.payload.dto.nurse;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single patient card inside a nurse flow-board column.
 */
@Getter
@Builder
public class NurseFlowPatientCardDTO {

    private UUID patientId;
    private String patientName;
    private String mrn;

    private UUID admissionId;

    /** AcuityLevel enum name (e.g. "LEVEL_3_MAJOR"). */
    private String acuityLevel;

    /** How many minutes the patient has been in the current status. */
    private long waitMinutes;

    private String roomBed;
    private String departmentName;
    private LocalDateTime admittedAt;
}
