package com.example.hms.model.education;

import com.example.hms.enums.EducationCategory;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks education topics discussed during prenatal visits
 */
@Entity
@Table(
    name = "visit_education_documentation",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_visit_education_encounter", columnList = "encounter_id"),
        @Index(name = "idx_visit_education_patient", columnList = "patient_id"),
        @Index(name = "idx_visit_education_category", columnList = "category")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitEducationDocumentation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID encounterId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID staffId;

    @Column(nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EducationCategory category;

    @Column(nullable = false, length = 500)
    private String topicDiscussed;

    @Column(columnDefinition = "TEXT")
    private String discussionNotes;

    // Resources provided
    @ElementCollection
    @CollectionTable(
        name = "visit_education_resources_provided",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "documentation_id")
    )
    @Column(name = "resource_id")
    @Builder.Default
    private java.util.Set<UUID> resourcesProvided = new java.util.HashSet<>();

    // Patient engagement
    @Column(nullable = false)
    @Builder.Default
    private Boolean patientEngaged = true;

    @Column(length = 1000)
    private String patientQuestions;

    @Column(length = 1000)
    private String patientConcerns;

    @Column(nullable = false)
    @Builder.Default
    private Boolean patientUnderstood = true;

    @Column(length = 1000)
    private String comprehensionNotes;

    // Follow-up
    @Column(nullable = false)
    @Builder.Default
    private Boolean requiresFollowUp = false;

    @Column(length = 1000)
    private String followUpPlan;

    private LocalDateTime followUpScheduledFor;

    // Specific topics
    @Column(nullable = false)
    @Builder.Default
    private Boolean nutritionDiscussed = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean exerciseDiscussed = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean breastfeedingDiscussed = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean birthPlanDiscussed = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean warningSignsDiscussed = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean mentalHealthDiscussed = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
