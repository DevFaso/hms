package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Record of who accessed the patient's data")
public class AccessLogEntryDTO {

    @Schema(description = "Audit event ID")
    private UUID id;

    @Schema(description = "Who performed the action (user display name or system)")
    private String actor;

    @Schema(description = "Type of event (e.g. VIEW, UPDATE, EXPORT)")
    private String eventType;

    @Schema(description = "What was accessed (entity type)")
    private String entityType;

    @Schema(description = "Resource ID that was accessed")
    private String resourceId;

    @Schema(description = "Short description of the action")
    private String description;

    @Schema(description = "Status of the event (SUCCESS, FAILURE)")
    private String status;

    @Schema(description = "When the access occurred")
    private LocalDateTime timestamp;
}
