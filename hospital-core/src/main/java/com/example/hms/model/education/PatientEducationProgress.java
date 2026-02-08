package com.example.hms.model.education;

import com.example.hms.enums.EducationComprehensionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks patient interaction with educational resources
 */
@Entity
@Table(
    name = "patient_education_progress",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_education_progress_patient", columnList = "patient_id"),
        @Index(name = "idx_education_progress_resource", columnList = "resource_id"),
        @Index(name = "idx_education_progress_status", columnList = "comprehension_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID resourceId;

    @Column(nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EducationComprehensionStatus comprehensionStatus;

    // Progress tracking
    @Column(nullable = false)
    @Builder.Default
    private Integer progressPercentage = 0;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime lastAccessedAt;

    // Engagement metrics
    @Column(nullable = false)
    @Builder.Default
    private Long timeSpentSeconds = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Integer accessCount = 0;

    // Feedback
    private Integer rating; // 1-5 stars

    @Column(length = 2000)
    private String feedback;

    @Column(nullable = false)
    @Builder.Default
    private Boolean needsClarification = false;

    @Column(length = 1000)
    private String clarificationRequest;

    @Column(nullable = false)
    @Builder.Default
    private Boolean confirmedUnderstanding = false;

    // Provider notes
    private UUID providerId;

    @Column(length = 1000)
    private String providerNotes;

    private LocalDateTime discussedWithProviderAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
