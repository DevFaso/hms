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
public class WaitlistEntryRequestDTO {

    /** QA D1258: refused with fieldErrors.patientId before any lookup. */
    @NotNull(message = "{waitlist.patient.required}")
    private UUID patientId;

    @NotNull(message = "{waitlist.department.required}")
    private UUID departmentId;

    private UUID preferredProviderId;

    private LocalDate requestedDateFrom;
    private LocalDate requestedDateTo;

    /** ROUTINE | URGENT | STAT */
    @Builder.Default
    private String priority = "ROUTINE";

    private String reason;
}
