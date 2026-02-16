package com.example.hms.payload.dto.signature;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for revoking a digital signature.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for revoking a digital signature")
public class SignatureRevocationRequestDTO {

    @NotBlank(message = "Revocation reason is required")
    @Size(max = 1000, message = "Revocation reason cannot exceed 1000 characters")
    @Schema(description = "Reason for revoking the signature", 
        example = "Error in report, needs correction")
    private String revocationReason;

    @Size(max = 45, message = "IP address cannot exceed 45 characters")
    @Schema(description = "IP address from which revocation is performed", 
        example = "198.51.100.1")
    private String ipAddress;

    @Size(max = 500, message = "Device info cannot exceed 500 characters")
    @Schema(description = "Device/user agent information")
    private String deviceInfo;
}
