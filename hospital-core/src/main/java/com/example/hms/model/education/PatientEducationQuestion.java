package com.example.hms.model.education;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Patient questions about educational content or pregnancy topics
 */
@Entity
@Table(
    name = "patient_education_questions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_education_question_patient", columnList = "patient_id"),
        @Index(name = "idx_education_question_resource", columnList = "resource_id"),
        @Index(name = "idx_education_question_answered", columnList = "is_answered")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    private UUID resourceId; // Optional - question may relate to specific resource

    @Column(nullable = false)
    private UUID hospitalId;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(length = 100)
    private String category;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isUrgent = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAnswered = false;

    @Column(columnDefinition = "TEXT")
    private String answer;

    private UUID answeredByStaffId;

    private LocalDateTime answeredAt;

    @Column(length = 1000)
    private String providerNotes;

    // Follow-up
    @Column(nullable = false)
    @Builder.Default
    private Boolean requiresInPersonDiscussion = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean appointmentScheduled = false;

    private UUID relatedAppointmentId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
