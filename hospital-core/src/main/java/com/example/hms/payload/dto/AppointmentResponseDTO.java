package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentResponseDTO {

    private UUID id;

    private UUID patientId;
    private String patientName;
    private String patientEmail;
    private String patientPhone;

    private UUID staffId;
    private UUID staffUserId;
    private String staffName;
    private String staffEmail;

    private UUID hospitalId;
    private String hospitalName;
    private String hospitalAddress;

    private UUID treatmentId;
    private String treatmentName;
    private String treatmentDescription;

    private UUID createdById;
    private String createdByName;

    private String reason;
    private String notes;

    private LocalDate appointmentDate;
    private LocalTime startTime;
    private LocalTime endTime;

        private UUID departmentId;
        private String departmentName;
        private AppointmentStatus status;

    /** Timestamp when the patient was checked in (MVP 1). */
    private LocalDateTime checkedInAt;

    /** Whether the patient completed pre-check-in via the patient portal (MVP 4). */
    private Boolean preCheckedIn;

    /** Timestamp when the patient submitted pre-check-in (MVP 4). */
    private LocalDateTime preCheckinTimestamp;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
