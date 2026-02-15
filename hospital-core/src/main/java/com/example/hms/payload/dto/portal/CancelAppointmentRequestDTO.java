package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to cancel their own appointment")
public class CancelAppointmentRequestDTO {

    @NotNull
    @Schema(description = "ID of the appointment to cancel", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID appointmentId;

    @Size(max = 500)
    @Schema(description = "Optional reason for cancellation", example = "Schedule conflict")
    private String reason;
}
