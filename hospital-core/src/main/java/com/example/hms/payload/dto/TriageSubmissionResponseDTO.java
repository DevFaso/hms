package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response returned after a successful triage submission (MVP 2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriageSubmissionResponseDTO {

    private UUID encounterId;
    private String encounterStatus;
    private Integer esiScore;
    private String urgency;
    private String roomAssignment;
    private LocalDateTime triageTimestamp;
    private LocalDateTime roomedTimestamp;
    private String chiefComplaint;

    /** ID of the created PatientVitalSign record. */
    private UUID vitalSignId;
}
