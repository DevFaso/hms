package com.example.hms.payload.dto;

import com.example.hms.enums.BreakGlassReviewOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
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

    @Schema(description = "QA D839: the patient's name, so the reviewer reads who, not an id.")
    private String patientName;

    @Schema(description = "QA D839: the patient's MRN at the session's hospital; null when not registered there.")
    private String patientMrn;

    @Schema(description = "User who declared the override.")
    private UUID userId;

    @Schema(description = "QA D839: the declarer's name (first and last), not their username.")
    private String userDisplayName;

    @Schema(description = "Display name of the declaring user (denormalised for the audit screen).")
    private String userName;

    @Schema(description = "Hospital under whose authority the override was invoked.")
    private UUID hospitalId;

    @Schema(description = "Display name of the hospital.")
    private String hospitalName;

    @Schema(description = "Clinical justification recorded at declare time.")
    private String reason;

    @Schema(description = "When the session began.")
    private OffsetDateTime startedAt;

    @Schema(description = "When the session expires (or expired), with the server offset so a browser in another zone counts down correctly.")
    private OffsetDateTime expiresAt;

    @Schema(description = "When the session was revoked early; null if it ran to expiry or is still live.")
    private OffsetDateTime revokedAt;

    @Schema(description = "User who revoked the session (may differ from declaring user).")
    private UUID revokedByUserId;

    @Schema(description = "Optional reason captured on revocation.")
    private String revokeReason;

    @Schema(description = "Number of audited reads served under this session.")
    private int auditCount;

    @Schema(description = "True when the session is currently live (not revoked, not expired).")
    private boolean live;

    @Schema(description = "E8 #54 — when a compliance reviewer signed the session off; null until reviewed.")
    private OffsetDateTime reviewedAt;
    @Schema(description = "The reviewer.")
    private UUID reviewedByUserId;
    @Schema(description = "The reviewer's username.")
    private String reviewedByUserName;
    @Schema(description = "JUSTIFIED, NOT_JUSTIFIED or FOLLOW_UP.")
    private BreakGlassReviewOutcome reviewOutcome;
    @Schema(description = "The reviewer's note.")
    private String reviewNote;
    @Schema(description = "True once reviewed.")
    private boolean reviewed;
}
