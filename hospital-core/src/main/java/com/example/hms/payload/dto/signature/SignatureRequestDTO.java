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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for creating a digital signature on a clinical report.
 * Story #17: Generic Report Signing API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for signing a clinical report")
public class SignatureRequestDTO {

    @NotNull(message = "{signature.reportType.required}")
    @Schema(description = "Type of report being signed", example = "DISCHARGE_SUMMARY")
    private SignatureType reportType;

    @NotNull(message = "{signature.reportId.required}")
    @Schema(description = "ID of the report being signed")
    private UUID reportId;

    @NotNull(message = "{signature.signedByStaffId.required}")
    @Schema(description = "ID of the staff member signing the report")
    private UUID signedByStaffId;

    @NotNull(message = "{department.hospital.required}")
    @Schema(description = "ID of the hospital where signature is performed")
    private UUID hospitalId;

    @NotBlank(message = "{signature.signatureValue.required}")
    @Size(max = 2000, message = "{signature.signatureValue.size}")
    @Schema(description = "Electronic signature value (typed name, biometric reference, etc.)", 
        example = "Dr. John Smith")
    private String signatureValue;

    @Size(max = 2000, message = "{signature.signatureNotes.size}")
    @Schema(description = "Optional notes accompanying the signature", 
        example = "Reviewed all lab results and patient is cleared for discharge")
    private String signatureNotes;

    @Size(max = 45, message = "{signature.ipAddress.size}")
    @Schema(description = "IP address from which signature was created", example = "198.51.100.1")
    private String ipAddress;

    @Size(max = 500, message = "{signature.deviceInfo.size}")
    @Schema(description = "Device/user agent information", 
        example = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    private String deviceInfo;

    @Schema(description = "Optional expiration timestamp for the signature")
    private LocalDateTime expiresAt;

    @Size(max = 4000, message = "{signature.metadata.size}")
    @Schema(description = "Additional metadata as JSON string")
    private String metadata;
}
