package com.example.hms.payload.dto;

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
public class WaitlistEntryRequestDTO {

    private UUID patientId;
    private UUID departmentId;
    private UUID preferredProviderId;

    private LocalDate requestedDateFrom;
    private LocalDate requestedDateTo;

    /** ROUTINE | URGENT | STAT */
    @Builder.Default
    private String priority = "ROUTINE";

    private String reason;
}
