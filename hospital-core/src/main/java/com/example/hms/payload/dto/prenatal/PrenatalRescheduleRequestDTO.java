package com.example.hms.payload.dto.prenatal;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrenatalRescheduleRequestDTO {

    @NotNull
    private UUID appointmentId;

    @NotNull
    @Future
    private LocalDate newAppointmentDate;

    @NotNull
    private LocalTime newStartTime;

    private Integer durationMinutes;

    private UUID newStaffId;

    private String notes;
}
