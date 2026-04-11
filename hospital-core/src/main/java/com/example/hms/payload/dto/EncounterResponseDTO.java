package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterResponseDTO {

    private UUID id;

    private UUID patientId;
    private String patientName;
    private String patientEmail;
    private String patientPhoneNumber;

    private UUID staffId;
    private String staffName;
    private String staffEmail;
    private String staffPhoneNumber;

    private UUID departmentId;
    private String departmentName;

    private UUID hospitalId;
    private String hospitalAddress;
    private String hospitalEmail;
    private String hospitalPhoneNumber;
    private String hospitalName;

    private UUID appointmentId;
    private String appointmentReason;
    private String appointmentNotes;
    private String appointmentStatus;
    private String appointmentType;
    private LocalDateTime appointmentDate;

    private EncounterType encounterType;
    private EncounterStatus status;
    private LocalDateTime encounterDate;
    private String notes;

    /** Timestamp when the patient physically arrived / was checked in (MVP 1). */
    private LocalDateTime arrivalTimestamp;

    /** Chief complaint captured at check-in or triage (MVP 1). */
    private String chiefComplaint;

    /** ESI acuity score 1-5 (MVP 2). */
    private Integer esiScore;

    /** Exam room assignment (MVP 2). */
    private String roomAssignment;

    /** When triage was completed (MVP 2). */
    private LocalDateTime triageTimestamp;

    /** When patient was roomed (MVP 2). */
    private LocalDateTime roomedTimestamp;

    /** Encounter urgency derived from ESI score (MVP 2). */
    private String urgency;

    /** When nursing intake was completed (MVP 3). */
    private LocalDateTime nursingIntakeTimestamp;

    /** When the patient was formally checked out (MVP 6). */
    private LocalDateTime checkoutTimestamp;

    /** Follow-up / discharge instructions (MVP 6). */
    private String followUpInstructions;

    /** Discharge diagnoses as JSON array text (MVP 6). */
    private String dischargeDiagnoses;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
        // Audit fields
        private String createdBy;
        private String updatedBy;
    
        // Extensibility: custom fields
        private java.util.Map<String, Object> extraFields;

        private EncounterNoteResponseDTO note;

    /** Optional derived full name so clients don't concatenate */
    @JsonProperty("patientFullName")
    public String getPatientFullName() {
        if (patientName != null && !patientName.isBlank()) return patientName;
        return null;
    }

    @JsonProperty("staffFullName")
    public String getStaffFullName() {
        if (staffName != null && !staffName.isBlank()) return staffName;
        return null;
    }
}
