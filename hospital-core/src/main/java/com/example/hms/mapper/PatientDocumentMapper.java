package com.example.hms.mapper;

import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PatientDocumentMapper {

    public PatientDocumentResponseDTO toDto(PatientUploadedDocument doc) {
        if (doc == null) {
            return null;
        }
        User uploader = doc.getUploadedByUser();
        return PatientDocumentResponseDTO.builder()
                .id(doc.getId())
                .patientId(doc.getPatient() != null ? doc.getPatient().getId() : null)
                .uploadedByUserId(uploader != null ? uploader.getId() : null)
                .uploadedByDisplayName(resolveUploaderDisplayName(uploader))
                .documentType(doc.getDocumentType())
                .displayName(doc.getDisplayName())
                .fileUrl(doc.getFileUrl())
                .mimeType(doc.getMimeType())
                .fileSizeBytes(doc.getFileSizeBytes())
                .checksumSha256(doc.getChecksumSha256())
                .collectionDate(doc.getCollectionDate())
                .notes(doc.getNotes())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    /**
     * Sonar S3776 — extracted from {@link #toDto} so the mapper's cognitive
     * complexity drops below 15. Prefers "First Last" (with either side
     * optional); falls back to {@code username} when both name fields are
     * blank or null; returns {@code null} when there is no uploader at all.
     */
    private String resolveUploaderDisplayName(User uploader) {
        if (uploader == null) {
            return null;
        }
        String first = uploader.getFirstName();
        String last = uploader.getLastName();
        if (first == null && last == null) {
            return uploader.getUsername();
        }
        String combined = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        return combined.isBlank() ? uploader.getUsername() : combined;
    }
}
