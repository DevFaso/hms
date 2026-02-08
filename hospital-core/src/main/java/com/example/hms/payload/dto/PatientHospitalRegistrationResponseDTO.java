package com.example.hms.payload.dto;

import com.example.hms.enums.PatientStayStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatientHospitalRegistrationResponseDTO {

    private UUID id;                // still available for appointments
    private String mri;

    @JsonProperty("registrationCode")
    public String getRegistrationCode() {
        return mri;
    }

    private UUID patientId;
    private String patientUsername; // added for human readability
    private String patientFirstName;
    private String patientLastName;
    private String patientEmail;
    private String patientPhone;
    private String patientGender;

    private UUID hospitalId;
    private String hospitalName;
    private String hospitalCode;
    private String hospitalAddress;

    private LocalDate registrationDate;
    private boolean active;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    private PatientStayStatus stayStatus;
    private LocalDateTime stayStatusUpdatedAt;
    private String currentRoom;
    private String currentBed;
    private String attendingPhysicianName;
    private String readyForDischargeNote;
    private UUID readyByStaffId;

    @JsonProperty("patientFullName")
    public String getPatientFullName() {
        String f = patientFirstName == null ? "" : patientFirstName.trim();
        String l = patientLastName == null ? "" : patientLastName.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
