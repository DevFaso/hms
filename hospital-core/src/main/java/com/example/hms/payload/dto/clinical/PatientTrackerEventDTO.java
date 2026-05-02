package com.example.hms.payload.dto.clinical;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight WebSocket event broadcast when an encounter status transitions
 * — clients refresh the tracker board on receipt rather than polling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient tracker board event — emitted on encounter status transitions.")
public class PatientTrackerEventDTO {

    @Schema(description = "Hospital scope of the event (subscribers filter by hospitalId).")
    private UUID hospitalId;

    @Schema(description = "Department scope when applicable.")
    private UUID departmentId;

    @Schema(description = "Encounter that transitioned.")
    private UUID encounterId;

    @Schema(description = "Patient on the encounter.")
    private UUID patientId;

    @Schema(description = "Encounter status the lane moved FROM (nullable for new arrivals).")
    private String previousStatus;

    @Schema(description = "Encounter status the lane moved TO.")
    private String newStatus;

    @Schema(description = "Server timestamp the event was emitted at.")
    private LocalDateTime emittedAt;
}
