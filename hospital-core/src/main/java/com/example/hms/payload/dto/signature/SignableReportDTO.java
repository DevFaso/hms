package com.example.hms.payload.dto.signature;

import com.example.hms.enums.SignatureType;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One report the caller can sign (QA D627). The id is what the sign request
 * carries; the portal never shows it, only the type, patient and date.
 */
public record SignableReportDTO(SignatureType reportType, UUID reportId, String patientName,
                                LocalDateTime createdAt) {
}
