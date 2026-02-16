package com.example.hms.payload.dto.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for digital signature information.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Digital signature information response")
public class SignatureResponseDTO {

    @Schema(description = "Unique signature ID")
    private UUID id;

    @Schema(description = "Type of report signed", example = "DISCHARGE_SUMMARY")
    private SignatureType reportType;

    @Schema(description = "ID of the signed report")
    private UUID reportId;

    @Schema(description = "ID of staff member who signed")
    private UUID signedByStaffId;

    @Schema(description = "Name of staff member who signed", example = "Dr. John Smith")
    private String signedByName;

    @Schema(description = "ID of the hospital context")
    private UUID hospitalId;

    @Schema(description = "Name of the hospital")
    private String hospitalName;

    @Schema(description = "Electronic signature value (may be redacted for security)", 
        example = "Dr. John Smith")
    private String signatureValue;

    @Schema(description = "Timestamp when signature was created")
    private LocalDateTime signatureDateTime;

    @Schema(description = "Current status of the signature", example = "SIGNED")
    private SignatureStatus status;

    @Schema(description = "SHA-256 hash of signature for verification")
    private String signatureHash;

    @Schema(description = "Optional notes provided with signature")
    private String signatureNotes;

    @Schema(description = "IP address from which signature was created")
    private String ipAddress;

    @Schema(description = "Device/user agent information")
    private String deviceInfo;

    @Schema(description = "Reason for revocation (if revoked)")
    private String revocationReason;

    @Schema(description = "Timestamp when signature was revoked")
    private LocalDateTime revokedAt;

    @Schema(description = "User ID who revoked the signature")
    private UUID revokedByUserId;

    @Schema(description = "Display name of user who revoked")
    private String revokedByDisplay;

    @Schema(description = "Expiration timestamp (if applicable)")
    private LocalDateTime expiresAt;

    @Schema(description = "Number of times signature has been verified")
    private Integer verificationCount;

    @Schema(description = "Last verification timestamp")
    private LocalDateTime lastVerifiedAt;

    @Schema(description = "Whether signature is currently valid")
    private Boolean isValid;

    @Schema(description = "Audit trail entries (only included when specifically requested)")
    private List<SignatureAuditEntryDTO> auditLog;

    @Schema(description = "Additional metadata")
    private String metadata;

    @Schema(description = "Timestamp when record was created")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp when record was last updated")
    private LocalDateTime updatedAt;

    @Schema(description = "Version number for optimistic locking")
    private Long version;
}
