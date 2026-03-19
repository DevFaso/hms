package com.example.hms.payload.dto.nurse;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admission summary card for GET /nurse/admissions/pending.
 * Surfaces new arrivals (PENDING/ACTIVE within last 2 h) and AWAITING_DISCHARGE patients.
 */
@Getter
@Builder
public class NurseAdmissionSummaryDTO {

    private UUID admissionId;
    private UUID patientId;
    private String patientName;
    private String mrn;

    /** AdmissionStatus enum name, e.g. "PENDING", "ACTIVE", "AWAITING_DISCHARGE". */
    private String status;

    /** AcuityLevel enum name, e.g. "LEVEL_3_MAJOR". */
    private String acuityLevel;

    private String roomBed;
    private String departmentName;
    private String admittingDoctor;

    private LocalDateTime admissionDateTime;

    /** AdmissionType enum name, e.g. "EMERGENCY", "ELECTIVE". */
    private String admissionType;
}
