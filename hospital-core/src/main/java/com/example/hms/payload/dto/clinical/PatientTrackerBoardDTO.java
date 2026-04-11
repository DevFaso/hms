package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Hospital-wide patient tracker board grouped by encounter status lanes (MVP 5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientTrackerBoardDTO {

    /** Patients who have arrived / checked in but not yet triaged. */
    private List<PatientTrackerItemDTO> arrived;

    /** Patients currently being triaged. */
    private List<PatientTrackerItemDTO> triage;

    /** Patients triaged and waiting for the physician. */
    private List<PatientTrackerItemDTO> waitingForPhysician;

    /** Patients actively being seen by a provider. */
    private List<PatientTrackerItemDTO> inProgress;

    /** Patients waiting for lab/imaging results. */
    private List<PatientTrackerItemDTO> awaitingResults;

    /** Patients clinically done, pending discharge paperwork. */
    private List<PatientTrackerItemDTO> readyForDischarge;

    /** Total number of patients across all active lanes. */
    private int totalPatients;

    /** Average wait time (in minutes) across all patients in active lanes. */
    private long averageWaitMinutes;
}
