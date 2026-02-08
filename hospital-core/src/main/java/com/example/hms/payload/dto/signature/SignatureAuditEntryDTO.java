package com.example.hms.payload.dto.signature;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for signature audit log entries.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Audit trail entry for a digital signature")
public class SignatureAuditEntryDTO {

    @Schema(description = "Action performed", example = "SIGNED")
    private String action;

    @Schema(description = "User ID who performed the action")
    private UUID performedByUserId;

    @Schema(description = "Display name of user who performed the action", example = "Dr. Jane Doe")
    private String performedByDisplay;

    @Schema(description = "Timestamp when action occurred")
    private LocalDateTime performedAt;

    @Schema(description = "Additional details about the action")
    private String details;

    @Schema(description = "IP address from which action was performed")
    private String ipAddress;

    @Schema(description = "Device/user agent information")
    private String deviceInfo;
}
