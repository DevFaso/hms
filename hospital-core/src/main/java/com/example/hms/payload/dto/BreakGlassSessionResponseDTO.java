package com.example.hms.payload.dto;

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
@Schema(description = "A break-the-glass emergency-access session.")
public class BreakGlassSessionResponseDTO {

    @Schema(description = "Session id.")
    private UUID id;

    @Schema(description = "Patient whose chart is unlocked under the override.")
    private UUID patientId;

    @Schema(description = "User who declared the override.")
    private UUID userId;

    @Schema(description = "Display name of the declaring user (denormalised for the audit screen).")
    private String userName;

    @Schema(description = "Hospital under whose authority the override was invoked.")
    private UUID hospitalId;

    @Schema(description = "Display name of the hospital.")
    private String hospitalName;

    @Schema(description = "Clinical justification recorded at declare time.")
    private String reason;

    @Schema(description = "When the session began.")
    private LocalDateTime startedAt;

    @Schema(description = "When the session expires (or expired).")
    private LocalDateTime expiresAt;

    @Schema(description = "When the session was revoked early; null if it ran to expiry or is still live.")
    private LocalDateTime revokedAt;

    @Schema(description = "User who revoked the session (may differ from declaring user).")
    private UUID revokedByUserId;

    @Schema(description = "Optional reason captured on revocation.")
    private String revokeReason;

    @Schema(description = "Number of audited reads served under this session.")
    private int auditCount;

    @Schema(description = "True when the session is currently live (not revoked, not expired).")
    private boolean live;
}
