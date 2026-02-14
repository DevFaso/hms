package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EducationResourceResponseDTO {
    private UUID id;
    private String title;
    private String description;
    private EducationResourceType resourceType;
    private EducationCategory category;
    private Set<String> tags;
    private String contentUrl;
    private String textContent;
    private String thumbnailUrl;
    private String videoUrl;
    private Integer estimatedDuration;
    private Boolean isActive;
    private Boolean isEvidenceBased;
    private String evidenceSource;
    private Boolean isHighRiskRelevant;
    private Boolean isWarningSignContent;
    private String primaryLanguage;
    private Map<String, String> translatedTitles;
    private Map<String, String> translatedContent;
    private Boolean isCulturallySensitive;
    private List<String> culturalNotes;
    private Set<UUID> relatedResourceIds;
    private UUID organizationId;
    private UUID hospitalId;
    private String createdBy;
    private String lastModifiedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private Long viewCount;
    private Long completionCount;
    private Double averageRating;
    private Long ratingCount;
}
