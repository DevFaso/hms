package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistEntryResponseDTO {

    private UUID id;
    private UUID hospitalId;

    private UUID patientId;
    private String patientName;
    private String mrn;

    private UUID departmentId;
    private String departmentName;

    private UUID preferredProviderId;
    private String preferredProviderName;

    private LocalDate requestedDateFrom;
    private LocalDate requestedDateTo;

    private String priority;
    private String reason;

    /** WAITING | OFFERED | CLOSED */
    private String status;

    private UUID offeredAppointmentId;

    private LocalDateTime createdAt;
    private String createdBy;
}
