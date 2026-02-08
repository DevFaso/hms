package com.example.hms.mapper;

import com.example.hms.model.education.VisitEducationDocumentation;
import com.example.hms.payload.dto.education.VisitEducationDocumentationRequestDTO;
import com.example.hms.payload.dto.education.VisitEducationDocumentationResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class VisitEducationDocumentationMapper {
    
    public VisitEducationDocumentationResponseDTO toResponseDTO(VisitEducationDocumentation entity) {
        if (entity == null) return null;
        
        return VisitEducationDocumentationResponseDTO.builder()
            .id(entity.getId())
            .encounterId(entity.getEncounterId())
            .patientId(entity.getPatientId())
            .staffId(entity.getStaffId())
            .hospitalId(entity.getHospitalId())
            .category(entity.getCategory())
            .topicDiscussed(entity.getTopicDiscussed())
            .discussionNotes(entity.getDiscussionNotes())
            .resourcesProvided(copySet(entity.getResourcesProvided()))
            .patientEngaged(entity.getPatientEngaged())
            .patientQuestions(entity.getPatientQuestions())
            .patientConcerns(entity.getPatientConcerns())
            .patientUnderstood(entity.getPatientUnderstood())
            .comprehensionNotes(entity.getComprehensionNotes())
            .requiresFollowUp(entity.getRequiresFollowUp())
            .followUpPlan(entity.getFollowUpPlan())
            .followUpScheduledFor(entity.getFollowUpScheduledFor())
            .nutritionDiscussed(entity.getNutritionDiscussed())
            .exerciseDiscussed(entity.getExerciseDiscussed())
            .breastfeedingDiscussed(entity.getBreastfeedingDiscussed())
            .birthPlanDiscussed(entity.getBirthPlanDiscussed())
            .warningSignsDiscussed(entity.getWarningSignsDiscussed())
            .mentalHealthDiscussed(entity.getMentalHealthDiscussed())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public VisitEducationDocumentation toEntity(VisitEducationDocumentationRequestDTO dto) {
        if (dto == null) return null;

        VisitEducationDocumentation entity = VisitEducationDocumentation.builder()
            .encounterId(dto.getEncounterId())
            .patientId(dto.getPatientId())
            .category(dto.getCategory())
            .topicDiscussed(dto.getTopicDiscussed())
            .build();

        updateEntityFromDTO(dto, entity);
        return entity;
    }
    
    public void updateEntityFromDTO(VisitEducationDocumentationRequestDTO dto, VisitEducationDocumentation entity) {
        if (dto == null || entity == null) return;

        applyIfNotNull(dto.getCategory(), entity::setCategory);
        applyIfNotNull(dto.getTopicDiscussed(), entity::setTopicDiscussed);
        applyIfNotNull(dto.getDiscussionNotes(), entity::setDiscussionNotes);
        applyIfNotNull(dto.getResourcesProvided(), value -> entity.setResourcesProvided(copySet(value)));
        applyIfNotNull(dto.getPatientEngaged(), entity::setPatientEngaged);
        applyIfNotNull(dto.getNutritionDiscussed(), entity::setNutritionDiscussed);
        applyIfNotNull(dto.getExerciseDiscussed(), entity::setExerciseDiscussed);
        applyIfNotNull(dto.getBreastfeedingDiscussed(), entity::setBreastfeedingDiscussed);
        applyIfNotNull(dto.getBirthPlanDiscussed(), entity::setBirthPlanDiscussed);
        applyIfNotNull(dto.getWarningSignsDiscussed(), entity::setWarningSignsDiscussed);
        applyIfNotNull(dto.getPatientQuestions(), entity::setPatientQuestions);
        applyIfNotNull(dto.getPatientConcerns(), entity::setPatientConcerns);
        applyIfNotNull(dto.getPatientUnderstood(), entity::setPatientUnderstood);
        applyIfNotNull(dto.getComprehensionNotes(), entity::setComprehensionNotes);
        applyIfNotNull(dto.getRequiresFollowUp(), entity::setRequiresFollowUp);
        applyIfNotNull(dto.getFollowUpPlan(), entity::setFollowUpPlan);
        applyIfNotNull(dto.getFollowUpScheduledFor(), entity::setFollowUpScheduledFor);
        applyIfNotNull(dto.getMentalHealthDiscussed(), entity::setMentalHealthDiscussed);
    }

    private <T> java.util.Set<T> copySet(java.util.Set<T> source) {
        return source == null ? null : new java.util.HashSet<>(source);
    }

    private <T> void applyIfNotNull(T value, java.util.function.Consumer<T> consumer) {
        if (value != null) consumer.accept(value);
    }
}
