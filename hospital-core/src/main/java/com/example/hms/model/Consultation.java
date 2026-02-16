package com.example.hms.model;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "consultations",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_consultation_patient", columnList = "patient_id"),
        @Index(name = "idx_consultation_hospital", columnList = "hospital_id"),
        @Index(name = "idx_consultation_requesting", columnList = "requesting_provider_id"),
        @Index(name = "idx_consultation_consultant", columnList = "consultant_id"),
        @Index(name = "idx_consultation_status", columnList = "status"),
        @Index(name = "idx_consultation_created", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Consultation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consultation_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consultation_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requesting_provider_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consultation_requesting_provider"))
    private Staff requestingProvider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultant_id",
        foreignKey = @ForeignKey(name = "fk_consultation_consultant"))
    private Staff consultant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_consultation_encounter"))
    private Encounter encounter;

    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_type", nullable = false, length = 50)
    private ConsultationType consultationType;

    @Column(name = "specialty_requested", nullable = false, length = 100)
    private String specialtyRequested;

    @Column(name = "reason_for_consult", nullable = false, columnDefinition = "TEXT")
    private String reasonForConsult;

    @Column(name = "clinical_question", columnDefinition = "TEXT")
    private String clinicalQuestion;

    @Column(name = "relevant_history", columnDefinition = "TEXT")
    private String relevantHistory;

    @Column(name = "current_medications", columnDefinition = "TEXT")
    private String currentMedications;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 30)
    private ConsultationUrgency urgency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private ConsultationStatus status = ConsultationStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "consultant_note", columnDefinition = "TEXT")
    private String consultantNote;

    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    @Column(name = "follow_up_required")
    private Boolean followUpRequired;

    @Column(name = "follow_up_instructions", columnDefinition = "TEXT")
    private String followUpInstructions;

    @Column(name = "sla_due_by")
    private LocalDateTime slaDueBy;

    @Column(name = "is_curbside")
    @Builder.Default
    private Boolean isCurbside = Boolean.FALSE;
}
