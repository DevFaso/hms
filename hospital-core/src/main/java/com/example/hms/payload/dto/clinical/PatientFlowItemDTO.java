package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for a patient card in the patient-flow kanban board.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientFlowItemDTO {

    private UUID patientId;
    private UUID encounterId;
    private UUID admissionId;
    private String patientName;
    private String room;
    private long elapsedMinutes;
    private String nurseAssigned;
    private String blockerTag;
    private String urgency;      // LOW, ROUTINE, URGENT, EMERGENT
    private String state;        // encounter/flow state label
    private String flowSource;   // "ENCOUNTER" or "ADMISSION"
}
