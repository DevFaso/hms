package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to self-schedule an appointment")
public class PortalBookAppointmentRequestDTO {

    @NotNull
    @Schema(description = "Hospital where the appointment should be booked",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @NotNull
    @Schema(description = "Department for the appointment",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID departmentId;

    @Schema(description = "Preferred provider (staff). If omitted, any available provider in the department is assigned")
    private UUID staffId;

    @NotNull
    @FutureOrPresent
    @Schema(description = "Requested appointment date", example = "2026-05-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate date;

    @NotNull
    @Schema(description = "Requested start time", example = "09:00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime startTime;

    @Schema(description = "Requested end time (defaults to startTime + 30 min if omitted)", example = "09:30")
    private LocalTime endTime;

    @Size(max = 500)
    @Schema(description = "Reason for the visit", example = "Annual physical exam")
    private String reason;

    @Size(max = 1000)
    @Schema(description = "Additional notes for the provider")
    private String notes;
}
