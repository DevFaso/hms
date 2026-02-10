package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientPrimaryCareResponseDTO {
    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID assignmentId;
    private UUID doctorUserId;
    private String doctorDisplay;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean current;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
