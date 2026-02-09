package com.example.hms.mapper;

import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.model.signature.SignatureAuditEntry;
import com.example.hms.payload.dto.signature.SignatureAuditEntryDTO;
import com.example.hms.payload.dto.signature.SignatureResponseDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper for DigitalSignature entities and DTOs.
 * Story #17: Generic Report Signing API
 */
@Component
public class DigitalSignatureMapper {

    /**
     * Convert entity to response DTO
     */
    public SignatureResponseDTO toResponseDTO(DigitalSignature entity) {
        return toResponseDTO(entity, false);
    }

    /**
     * Convert entity to response DTO with optional audit log inclusion
     */
    public SignatureResponseDTO toResponseDTO(DigitalSignature entity, boolean includeAuditLog) {
        if (entity == null) {
            return null;
        }

        SignatureResponseDTO.SignatureResponseDTOBuilder builder = SignatureResponseDTO.builder()
            .id(entity.getId())
            .reportType(entity.getReportType())
            .reportId(entity.getReportId())
            .signedByStaffId(entity.getSignedBy() != null ? entity.getSignedBy().getId() : null)
            .signedByName(entity.getSignedBy() != null ? entity.getSignedBy().getFullName() : "Unknown")
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : "Unknown")
            .signatureValue(entity.getSignatureValue())
            .signatureDateTime(entity.getSignatureDateTime())
            .status(entity.getStatus())
            .signatureHash(entity.getSignatureHash())
            .signatureNotes(entity.getSignatureNotes())
            .ipAddress(entity.getIpAddress())
            .deviceInfo(entity.getDeviceInfo())
            .revocationReason(entity.getRevocationReason())
            .revokedAt(entity.getRevokedAt())
            .revokedByUserId(entity.getRevokedByUserId())
            .revokedByDisplay(entity.getRevokedByDisplay())
            .expiresAt(entity.getExpiresAt())
            .verificationCount(entity.getVerificationCount())
            .lastVerifiedAt(entity.getLastVerifiedAt())
            .isValid(entity.isValid())
            .metadata(entity.getMetadata())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .version(entity.getVersion());

        // Include audit log if requested
        if (includeAuditLog && entity.getAuditLog() != null && !entity.getAuditLog().isEmpty()) {
            List<SignatureAuditEntryDTO> auditLogDTOs = entity.getAuditLog().stream()
                .map(this::toAuditEntryDTO)
                .toList();
            builder.auditLog(auditLogDTOs);
        }

        return builder.build();
    }

    /**
     * Convert audit entry to DTO
     */
    public SignatureAuditEntryDTO toAuditEntryDTO(SignatureAuditEntry entry) {
        if (entry == null) {
            return null;
        }

        return SignatureAuditEntryDTO.builder()
            .action(entry.getAction())
            .performedByUserId(entry.getPerformedByUserId())
            .performedByDisplay(entry.getPerformedByDisplay())
            .performedAt(entry.getPerformedAt())
            .details(entry.getDetails())
            .ipAddress(entry.getIpAddress())
            .deviceInfo(entry.getDeviceInfo())
            .build();
    }

    /**
     * Convert list of audit entries to DTOs
     */
    public List<SignatureAuditEntryDTO> toAuditEntryDTOs(List<SignatureAuditEntry> entries) {
        if (entries == null) {
            return List.of();
        }

        return entries.stream()
            .map(this::toAuditEntryDTO)
            .toList();
    }

    /**
     * Convert response DTO with redacted sensitive information (for public endpoints)
     */
    public SignatureResponseDTO toRedactedResponseDTO(DigitalSignature entity) {
        SignatureResponseDTO dto = toResponseDTO(entity, false);
        
        if (dto != null) {
            // Redact sensitive information
            dto.setSignatureValue("[REDACTED]");
            dto.setIpAddress(null);
            dto.setDeviceInfo(null);
            dto.setSignatureHash(null);
        }
        
        return dto;
    }
}
