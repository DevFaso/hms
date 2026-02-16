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
        private AppointmentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
