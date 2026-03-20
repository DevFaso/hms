package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Patient-portal DTO for requesting a new appointment.
 * The patient identity is resolved from the JWT — never sent by the client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalAppointmentRequestDTO {

    @NotNull(message = "Department is required")
    private UUID departmentId;

    private UUID staffId;

    @NotNull(message = "Appointment date is required")
    @FutureOrPresent(message = "Appointment date must be today or in the future")
    private LocalDate appointmentDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    @NotBlank(message = "Reason is required")
    private String reason;

    private String notes;
}
