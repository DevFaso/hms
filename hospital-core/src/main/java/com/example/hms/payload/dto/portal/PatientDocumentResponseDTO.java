package com.example.hms.payload.dto.portal;

import com.example.hms.enums.PatientDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientDocumentResponseDTO {
    private UUID id;
    private UUID patientId;
    private UUID uploadedByUserId;
    private String uploadedByDisplayName;
    private PatientDocumentType documentType;
    private String displayName;
    private String fileUrl;
    private String mimeType;
    private Long fileSizeBytes;
    private String checksumSha256;
    private LocalDate collectionDate;
    private String notes;
    private LocalDateTime createdAt;
}
