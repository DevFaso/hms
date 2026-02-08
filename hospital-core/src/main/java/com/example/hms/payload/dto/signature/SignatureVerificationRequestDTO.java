package com.example.hms.payload.dto.signature;

import com.example.hms.enums.SignatureType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for verifying a digital signature.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for verifying a digital signature")
public class SignatureVerificationRequestDTO {

    @NotNull(message = "Report type is required")
    @Schema(description = "Type of report being verified", example = "DISCHARGE_SUMMARY")
    private SignatureType reportType;

    @NotNull(message = "Report ID is required")
    @Schema(description = "ID of the report being verified")
    private UUID reportId;

    @NotBlank(message = "Signature value is required for verification")
    @Size(max = 2000, message = "Signature value cannot exceed 2000 characters")
    @Schema(description = "Signature value to verify against stored hash", 
        example = "Dr. John Smith")
    private String signatureValue;

    @Schema(description = "Optional signature ID to verify specific signature")
    private UUID signatureId;

    @Size(max = 45, message = "IP address cannot exceed 45 characters")
    @Schema(description = "IP address from which verification is performed", 
        example = "192.168.1.100") // NOSONAR java:S1313 - OpenAPI doc example only, not a real IP binding
    private String ipAddress;

    @Size(max = 500, message = "Device info cannot exceed 500 characters")
    @Schema(description = "Device/user agent information")
    private String deviceInfo;
}
