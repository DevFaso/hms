package com.example.hms.payload.dto.signature;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for signature verification results.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of signature verification")
public class SignatureVerificationResponseDTO {

    @Schema(description = "Whether signature is valid")
    private Boolean isValid;

    @Schema(description = "ID of the verified signature (if found)")
    private UUID signatureId;

    @Schema(description = "Verification message", 
        example = "Signature verified successfully")
    private String message;

    @Schema(description = "ID of staff member who created the signature")
    private UUID signedByStaffId;

    @Schema(description = "Name of staff member who created the signature")
    private String signedByName;

    @Schema(description = "When the signature was originally created")
    private LocalDateTime signatureDateTime;

    @Schema(description = "Current status of the signature", example = "SIGNED")
    private String status;

    @Schema(description = "Reason why signature is invalid (if applicable)")
    private String invalidReason;

    @Schema(description = "Number of times this signature has been verified")
    private Integer verificationCount;

    @Schema(description = "Timestamp of this verification")
    private LocalDateTime verifiedAt;
}
