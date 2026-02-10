package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EducationResourceRequestDTO {
    
    @NotBlank(message = "Title is required")
    @Size(max = 500)
    private String title;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "Resource type is required")
    private EducationResourceType resourceType;

    @NotNull(message = "Category is required")
    private EducationCategory category;

    private Set<String> tags;

    @Size(max = 1000)
    private String contentUrl;

    private String textContent;

    @Size(max = 500)
    private String thumbnailUrl;

    @Size(max = 500)
    private String videoUrl;

    private Integer estimatedDuration;

    private Boolean isEvidenceBased;

    @Size(max = 1000)
    private String evidenceSource;

    private Boolean isHighRiskRelevant;

    private Boolean isWarningSignContent;

    @NotBlank
    @Size(max = 10)
    @Builder.Default
    private String primaryLanguage = "fr";

    private Map<String, String> translatedTitles;

    private Map<String, String> translatedContent;

    private Boolean isCulturallySensitive;

    private List<String> culturalNotes;

    private Set<UUID> relatedResourceIds;

    private UUID organizationId;

    private UUID hospitalId;
}
