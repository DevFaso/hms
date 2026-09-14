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
    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;
    @NotNull(message = "{patientPrimaryCare.assignmentId.required}")
    private UUID assignmentId;
    @NotNull(message = "{patientPrimaryCare.startDate.required}")
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
}
