package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for a single patient card on the hospital-wide patient tracker board (MVP 5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientTrackerItemDTO {

    private UUID patientId;
    private String patientName;
    private String mrn;

    private UUID appointmentId;
    private UUID encounterId;

    /** Current encounter status label (e.g. ARRIVED, TRIAGE, IN_PROGRESS). */
    private String currentStatus;

    private String roomAssignment;
    private String assignedProvider;
    private String departmentName;

    private LocalDateTime arrivalTimestamp;
    private LocalDateTime triageTimestamp;

    /** Minutes since arrival (or since encounter creation if no arrival timestamp). */
    private long currentWaitMinutes;

    /** ESI acuity level (1-5) or derived urgency label (LOW, ROUTINE, URGENT, EMERGENT). */
    private String acuityLevel;

    /** Whether the patient completed pre-check-in via the patient portal. */
    private Boolean preCheckedIn;
}
