package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRequestDTO {

    private UUID id;

    @FutureOrPresent(message = "Appointment date must be today or in the future")
    private LocalDate appointmentDate;

    @NotNull(message = "Start time is required.")
    private LocalTime startTime;

    @NotNull(message = "End time is required.")
    private LocalTime endTime;


    // Accept either ID or alternative identifier for patient
    private UUID patientId;
    private String patientUsername;
    private String patientEmail;

    // Accept either ID or alternative identifier for staff
    private UUID staffId;
    private String staffEmail;
    private String staffUsername;

    // Accept either ID or alternative identifier for hospital
    private UUID hospitalId;
    private String hospitalCode;
    private String hospitalName;

    // Accept either ID or alternative identifier for treatment

    // Optionally allow department to be specified directly
    private UUID departmentId;
    private String departmentName;
    private String departmentCode;

    @NotNull(message = "Appointment status is required.")
    private AppointmentStatus status;

    private String reason;
    private String notes;
}
