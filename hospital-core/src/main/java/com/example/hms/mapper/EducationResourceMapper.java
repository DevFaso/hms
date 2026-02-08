package com.example.hms.mapper;

import com.example.hms.model.education.EducationResource;
import com.example.hms.payload.dto.education.EducationResourceRequestDTO;
import com.example.hms.payload.dto.education.EducationResourceResponseDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class EducationResourceMapper {
    
    public EducationResourceResponseDTO toResponseDTO(EducationResource entity) {
        if (entity == null) return null;
        
        return EducationResourceResponseDTO.builder()
            .id(entity.getId())
            .title(entity.getTitle())
            .description(entity.getDescription())
            .resourceType(entity.getResourceType())
            .category(entity.getCategory())
            .tags(copySet(entity.getTags()))
            .contentUrl(entity.getContentUrl())
            .textContent(entity.getTextContent())
            .thumbnailUrl(entity.getThumbnailUrl())
            .videoUrl(entity.getVideoUrl())
            .estimatedDuration(entity.getEstimatedDuration())
            .isActive(entity.getIsActive())
            .isEvidenceBased(entity.getIsEvidenceBased())
            .evidenceSource(entity.getEvidenceSource())
            .isHighRiskRelevant(entity.getIsHighRiskRelevant())
            .isWarningSignContent(entity.getIsWarningSignContent())
            .primaryLanguage(entity.getPrimaryLanguage())
            .translatedTitles(copyMap(entity.getTranslatedTitles()))
            .translatedContent(copyMap(entity.getTranslatedContent()))
            .isCulturallySensitive(entity.getIsCulturallySensitive())
            .culturalNotes(copyList(entity.getCulturalNotes()))
            .relatedResourceIds(copySet(entity.getRelatedResourceIds()))
            .organizationId(entity.getOrganizationId())
            .hospitalId(entity.getHospitalId())
            .createdBy(entity.getCreatedBy())
            .lastModifiedBy(entity.getLastModifiedBy())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .publishedAt(entity.getPublishedAt())
            .viewCount(entity.getViewCount())
            .completionCount(entity.getCompletionCount())
            .averageRating(entity.getAverageRating())
            .ratingCount(entity.getRatingCount())
            .build();
    }
    
    public EducationResource toEntity(EducationResourceRequestDTO dto) {
        if (dto == null) return null;
        
        EducationResource entity = EducationResource.builder()
            .title(dto.getTitle())
            .description(dto.getDescription())
            .resourceType(dto.getResourceType())
            .category(dto.getCategory())
            .primaryLanguage(dto.getPrimaryLanguage())
            .organizationId(dto.getOrganizationId())
            .hospitalId(dto.getHospitalId())
            .build();

        updateEntityFromDTO(dto, entity);
        return entity;
    }
    
    public void updateEntityFromDTO(EducationResourceRequestDTO dto, EducationResource entity) {
        if (dto == null || entity == null) return;

        applyIfNotNull(dto.getTitle(), entity::setTitle);
        applyIfNotNull(dto.getDescription(), entity::setDescription);
        applyIfNotNull(dto.getResourceType(), entity::setResourceType);
        applyIfNotNull(dto.getCategory(), entity::setCategory);
        applyIfNotNull(dto.getContentUrl(), entity::setContentUrl);
        applyIfNotNull(dto.getTextContent(), entity::setTextContent);
        applyIfNotNull(dto.getThumbnailUrl(), entity::setThumbnailUrl);
        applyIfNotNull(dto.getVideoUrl(), entity::setVideoUrl);
        applyIfNotNull(dto.getEstimatedDuration(), entity::setEstimatedDuration);
        applyIfNotNull(dto.getIsEvidenceBased(), entity::setIsEvidenceBased);
        applyIfNotNull(dto.getEvidenceSource(), entity::setEvidenceSource);
        applyIfNotNull(dto.getIsHighRiskRelevant(), entity::setIsHighRiskRelevant);
        applyIfNotNull(dto.getIsWarningSignContent(), entity::setIsWarningSignContent);
        applyIfNotNull(dto.getPrimaryLanguage(), entity::setPrimaryLanguage);
        applyIfNotNull(dto.getTranslatedTitles(), value -> entity.setTranslatedTitles(copyMap(value)));
        applyIfNotNull(dto.getTranslatedContent(), value -> entity.setTranslatedContent(copyMap(value)));
        applyIfNotNull(dto.getIsCulturallySensitive(), entity::setIsCulturallySensitive);
        applyIfNotNull(dto.getCulturalNotes(), value -> entity.setCulturalNotes(copyList(value)));
        applyIfNotNull(dto.getRelatedResourceIds(), value -> entity.setRelatedResourceIds(copySet(value)));
        applyIfNotNull(dto.getOrganizationId(), entity::setOrganizationId);
        applyIfNotNull(dto.getHospitalId(), entity::setHospitalId);
        applyIfNotNull(dto.getTags(), value -> entity.setTags(copySet(value)));
    }

    private <T> Set<T> copySet(Set<T> source) {
        return source == null ? null : new HashSet<>(source);
    }

    private <T> List<T> copyList(List<T> source) {
        return source == null ? null : new java.util.ArrayList<>(source);
    }

    private <K, V> Map<K, V> copyMap(Map<K, V> source) {
        return source == null ? null : new HashMap<>(source);
    }

    private <T> void applyIfNotNull(T value, Consumer<T> consumer) {
        if (value != null) {
            consumer.accept(value);
        }
    }
}
