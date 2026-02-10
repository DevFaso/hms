package com.example.hms.model;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(
    name = "patient_allergies",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_allergy_patient", columnList = "patient_id"),
        @Index(name = "idx_allergy_hospital", columnList = "hospital_id"),
        @Index(name = "idx_allergy_active", columnList = "active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PatientAllergy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_allergy_patient", value = ConstraintMode.CONSTRAINT))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_allergy_hospital", value = ConstraintMode.CONSTRAINT))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_allergy_staff", value = ConstraintMode.CONSTRAINT))
    private Staff recordedBy;

    @Column(name = "allergen_code", length = 64)
    private String allergenCode;

    @Column(name = "allergen_display", length = 255, nullable = false)
    private String allergenDisplay;

    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32)
    private AllergySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 32)
    private AllergyVerificationStatus verificationStatus;

    @Column(name = "reaction", length = 255)
    private String reaction;

    @Column(name = "reaction_notes", length = 1024)
    private String reactionNotes;

    @Column(name = "onset_date")
    private LocalDate onsetDate;

    @Column(name = "last_occurrence_date")
    private LocalDate lastOccurrenceDate;

    @Column(name = "recorded_date")
    private LocalDate recordedDate;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (allergenDisplay != null) {
            allergenDisplay = allergenDisplay.trim();
        }
        if (allergenCode != null) {
            allergenCode = allergenCode.trim();
        }
        if (category != null) {
            category = category.trim();
        }
        if (reaction != null) {
            reaction = reaction.trim();
        }
        if (sourceSystem != null) {
            sourceSystem = sourceSystem.trim();
        }
    }
}
