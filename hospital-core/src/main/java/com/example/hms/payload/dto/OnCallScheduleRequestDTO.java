package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Write side of the on-call rota (P2 #13). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create or update an on-call rota entry")
public class OnCallScheduleRequestDTO {

    @NotNull(message = "{onCallSchedule.staffId.required}")
    private UUID staffId;

    @Schema(description = "Department covered; optional for a hospital-wide rota")
    private UUID departmentId;

    @NotNull(message = "{onCallSchedule.startTime.required}")
    private OffsetDateTime startTime;

    @NotNull(message = "{onCallSchedule.endTime.required}")
    private OffsetDateTime endTime;

    @Size(max = 500)
    private String notes;
}
