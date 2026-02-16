package com.example.hms.mapper;

import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.payload.dto.education.PatientEducationProgressRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PatientEducationProgressMapper {
    
    public PatientEducationProgressResponseDTO toResponseDTO(PatientEducationProgress entity) {
        if (entity == null) return null;
        
        return PatientEducationProgressResponseDTO.builder()
            .id(entity.getId())
            .patientId(entity.getPatientId())
            .resourceId(entity.getResourceId())
            .hospitalId(entity.getHospitalId())
            .comprehensionStatus(entity.getComprehensionStatus())
            .progressPercentage(entity.getProgressPercentage())
            .startedAt(entity.getStartedAt())
            .completedAt(entity.getCompletedAt())
            .lastAccessedAt(entity.getLastAccessedAt())
            .timeSpentSeconds(entity.getTimeSpentSeconds())
            .accessCount(entity.getAccessCount())
            .rating(entity.getRating())
            .feedback(entity.getFeedback())
            .needsClarification(entity.getNeedsClarification())
            .clarificationRequest(entity.getClarificationRequest())
            .confirmedUnderstanding(entity.getConfirmedUnderstanding())
            .providerId(entity.getProviderId())
            .providerNotes(entity.getProviderNotes())
            .discussedWithProviderAt(entity.getDiscussedWithProviderAt())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }
    
    public void updateEntityFromDTO(PatientEducationProgressRequestDTO dto, PatientEducationProgress entity) {
        if (dto == null || entity == null) return;
        
        if (dto.getComprehensionStatus() != null) entity.setComprehensionStatus(dto.getComprehensionStatus());
        if (dto.getProgressPercentage() != null) entity.setProgressPercentage(dto.getProgressPercentage());
        if (dto.getRating() != null) entity.setRating(dto.getRating());
        if (dto.getFeedback() != null) entity.setFeedback(dto.getFeedback());
        if (dto.getNeedsClarification() != null) entity.setNeedsClarification(dto.getNeedsClarification());
        if (dto.getClarificationRequest() != null) entity.setClarificationRequest(dto.getClarificationRequest());
        if (dto.getConfirmedUnderstanding() != null) entity.setConfirmedUnderstanding(dto.getConfirmedUnderstanding());
        if (dto.getProviderNotes() != null) entity.setProviderNotes(dto.getProviderNotes());
    }
}
