package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

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
