package com.example.hms.payload.dto;

import com.example.hms.patient.dto.PatientInsuranceDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Front-desk queue item for the Insurance Issues panel.
 * Wraps appointment/patient context + the specific gap found.
 * {@link PatientInsuranceDto} is embedded when a policy record exists
 * (null for MISSING_INSURANCE).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsuranceIssueDTO {
    // ── Queue context ────────────────────────────────────────────
    private UUID appointmentId;
    private UUID patientId;
    private String patientName;
    private String mrn;
    private String appointmentTime;
    /** MISSING_INSURANCE | EXPIRED_INSURANCE | NO_PRIMARY */
    private String issueType;
    /** Appointment clinician (not insurer) */
    private String clinicianName;
    private String departmentName;

    // ── Policy detail (null when issueType = MISSING_INSURANCE) ─
    private PatientInsuranceDto insurance;
}
