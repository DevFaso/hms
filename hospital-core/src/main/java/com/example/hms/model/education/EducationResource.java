package com.example.hms.model.education;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationResourceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Educational resource for patient education hub
 */
@Entity
@Table(name = "education_resources", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EducationResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EducationResourceType resourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EducationCategory category;

    @ElementCollection
    @CollectionTable(
        name = "education_resource_tags",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @Column(name = "tag", length = 100)
    @Builder.Default
    private Set<String> tags = new HashSet<>();

    @Column(length = 1000)
    private String contentUrl;

    @Column(columnDefinition = "TEXT")
    private String textContent;

    @Column(length = 500)
    private String thumbnailUrl;

    @Column(length = 500)
    private String videoUrl;

    // Duration in minutes
    private Integer estimatedDuration;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEvidenceBased = true;

    @Column(length = 1000)
    private String evidenceSource;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isHighRiskRelevant = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isWarningSignContent = false;

    // Language support
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String primaryLanguage = "en";

    @ElementCollection
    @CollectionTable(
        name = "education_resource_translations",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @MapKeyColumn(name = "language_code")
    @Column(name = "translated_title", length = 500)
    @Builder.Default
    private Map<String, String> translatedTitles = new HashMap<>();

    @ElementCollection
    @CollectionTable(
        name = "education_resource_content_translations",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @MapKeyColumn(name = "language_code")
    @Column(name = "translated_content", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, String> translatedContent = new HashMap<>();

    // Cultural sensitivity
    @Column(nullable = false)
    @Builder.Default
    private Boolean isCulturallySensitive = true;

    @ElementCollection
    @CollectionTable(
        name = "education_resource_cultural_notes",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @Column(name = "note", length = 1000)
    @Builder.Default
    private List<String> culturalNotes = new ArrayList<>();

    // Related resources
    @ElementCollection
    @CollectionTable(
        name = "education_resource_related",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @Column(name = "related_resource_id")
    @Builder.Default
    private Set<UUID> relatedResourceIds = new HashSet<>();

    // Organization/Hospital specific
    private UUID organizationId;
    private UUID hospitalId;

    // Metadata
    @Column(nullable = false, length = 100)
    private String createdBy;

    @Column(length = 100)
    private String lastModifiedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime publishedAt;

    // Statistics
    @Column(nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long completionCount = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Double averageRating = 0.0;

    @Column(nullable = false)
    @Builder.Default
    private Long ratingCount = 0L;
}
