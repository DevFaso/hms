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
        String uploaderDisplayName = null;
        if (uploader != null) {
            String first = uploader.getFirstName();
            String last = uploader.getLastName();
            if (first != null || last != null) {
                uploaderDisplayName = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            }
            if (uploaderDisplayName == null || uploaderDisplayName.isBlank()) {
                uploaderDisplayName = uploader.getUsername();
            }
        }

        return PatientDocumentResponseDTO.builder()
                .id(doc.getId())
                .patientId(doc.getPatient() != null ? doc.getPatient().getId() : null)
                .uploadedByUserId(uploader != null ? uploader.getId() : null)
                .uploadedByDisplayName(uploaderDisplayName)
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
}
