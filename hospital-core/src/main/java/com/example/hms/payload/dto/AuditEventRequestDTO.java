package com.example.hms.payload.dto;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for logging an audit event.")
public class AuditEventRequestDTO {
    @Schema(description = "Human-readable name of the user performing the action.", example = "Sandra Off Admin")
    private String userName;

    @Schema(description = "Human-readable name of the role in the hospital context.", example = "Hospital Admin")
    private String roleName;

    @Schema(description = "Human-readable name of the hospital.", example = "General Hospital")
    private String hospitalName;

    @Schema(description = "Human-readable name of the target resource.", example = "John Doe")
    private String resourceName;

    @NotNull
    @Schema(description = "ID of the user performing the action.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;

    @NotNull
    @Schema(description = "Assignment ID of the user in the hospital context.", example = "512a9d3b-051f-4139-bfbf-f61c1b8ad2e3", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID assignmentId;

    @NotNull
    @Schema(description = "Type of event being logged.", requiredMode = Schema.RequiredMode.REQUIRED, example = "RECORD_SHARE")
    private AuditEventType eventType;

    @Schema(description = "Description of the event.", example = "Patient record shared with another hospital.")
    private String eventDescription;

    @Schema(description = "IP address of the user performing the action.", example = "198.51.100.1")
    private String ipAddress;

    @Schema(description = "Status of the event.", example = "SUCCESS")
    private AuditStatus status;

    @Schema(description = "Detailed payload or message associated with the event.")
    private Object details;

    @Schema(description = "Target resource UUID involved in the event.", example = "123e4567-e89b-12d3-a456-426614174000")
    private String resourceId;

    @Schema(description = "Entity type of the target resource (e.g., PATIENT, APPOINTMENT).", example = "PATIENT")
    private String entityType;

}
