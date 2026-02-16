package com.example.hms.payload.dto;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload containing audit event details.")
public class AuditEventResponseDTO {

    @Schema(description = "Audit event unique identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "ID of the user associated with this event.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID userId;

    @Schema(description = "Name of the user associated with this event.", example = "John Doe")
    private String userName;

    @Schema(description = "Type of the event.", example = "RECORD_SHARE")
    private AuditEventType eventType;

    @Schema(description = "Description of the event.", example = "Patient record shared successfully.")
    private String eventDescription;

    @Schema(description = "Timestamp when the event occurred.", example = "2025-05-19T08:30:00")
    private LocalDateTime eventTimestamp;

    @Schema(description = "IP address from where the event was initiated.", example = "198.51.100.1")
    private String ipAddress;

    @Schema(description = "Status of the event.", example = "SUCCESS")
    private AuditStatus status;

    @Schema(description = "Detailed data or metadata associated with the event.")
    private String details;

    @Schema(description = "Target resource UUID involved in the event.", example = "123e4567-e89b-12d3-a456-426614174000")
    private String resourceId;

    @Schema(description = "Name of the target resource involved in the event.", example = "Patient Record")
    private String resourceName;

    @Schema(description = "Entity type of the target resource.", example = "PATIENT")
    private String entityType;
}
