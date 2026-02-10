package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationComprehensionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationProgressResponseDTO {
    private UUID id;
    private UUID patientId;
    private UUID resourceId;
    private UUID hospitalId;
    private EducationComprehensionStatus comprehensionStatus;
    private Integer progressPercentage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime lastAccessedAt;
    private Long timeSpentSeconds;
    private Integer accessCount;
    private Integer rating;
    private String feedback;
    private Boolean needsClarification;
    private String clarificationRequest;
    private Boolean confirmedUnderstanding;
    private UUID providerId;
    private String providerNotes;
    private LocalDateTime discussedWithProviderAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
