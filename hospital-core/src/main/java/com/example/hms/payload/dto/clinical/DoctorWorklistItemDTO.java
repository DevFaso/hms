package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for a single item in the physician worklist.
 * Merges appointments, encounters, consults and rounding patients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorWorklistItemDTO {

    private UUID patientId;
    private UUID encounterId;
    private String patientName;
    private String mrn;
    private int age;
    private String sex;
    private String room;
    private String bed;
    private String location;
    private String chiefComplaint;
    private String urgency;       // LOW, ROUTINE, URGENT, EMERGENT
    private String encounterStatus; // CHECKED_IN, ROOMED, WAITING_FOR_PHYSICIAN, IN_PROGRESS, AWAITING_RESULTS, READY_FOR_DISCHARGE, COMPLETED
    private Integer waitMinutes;
    private String latestVitalsSummary;
    private List<String> alerts;
    private LocalDateTime updatedAt;
}
