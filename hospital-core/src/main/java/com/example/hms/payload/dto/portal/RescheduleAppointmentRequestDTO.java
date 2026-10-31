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
@Schema(description = "Patient request to reschedule their own appointment")
public class RescheduleAppointmentRequestDTO {

    @NotNull
    @Schema(description = "ID of the appointment to reschedule", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID appointmentId;

    @NotNull
    @FutureOrPresent
    @Schema(description = "Requested new date", example = "2025-03-15", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate newDate;

    @NotNull
    @Schema(description = "Requested new start time", example = "10:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalTime newStartTime;

    /**
     * QA D1060: IGNORED. The slot rules set the length at the new time; kept
     * only so an older app that still sends it is not refused.
     */
    @Schema(description = "Ignored: the clinic's slot rules set the visit length (QA D1060)", example = "10:30",
            deprecated = true)
    private LocalTime newEndTime;

    @Size(max = 500)
    @Schema(description = "Reason for rescheduling", example = "Need a later slot")
    private String reason;
}
