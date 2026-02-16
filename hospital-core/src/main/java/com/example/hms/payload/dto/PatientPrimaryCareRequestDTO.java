package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientPrimaryCareRequestDTO {
    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;
    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;
    @NotNull(message = "Start date is required")
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
}
