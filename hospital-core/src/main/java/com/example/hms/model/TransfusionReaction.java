package com.example.hms.model;

import com.example.hms.enums.TransfusionReactionSeverity;
import com.example.hms.enums.TransfusionReactionType;
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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * An adverse reaction to a transfusion (Tier 2 item 28).
 *
 * <p>Recording one STOPS the administration. That is not a convenience: the
 * first action in every transfusion-reaction protocol is to stop the infusion,
 * and a record that let the unit read as still running while a reaction was
 * being documented would describe something that must never happen.
 *
 * <p>{@link #unitReturnedToLab} matters because the implicated bag goes back
 * for the reaction workup — it is evidence, and it must not re-enter stock.
 */
@Entity
@Table(
    name = "transfusion_reactions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_reaction_administration", columnList = "administration_id"),
        @Index(name = "idx_reaction_patient",        columnList = "patient_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"administration", "patient", "hospital", "reportedBy"})
public class TransfusionReaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "administration_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_reaction_administration"))
    private TransfusionAdministration administration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_reaction_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_reaction_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 40)
    private TransfusionReactionType reactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private TransfusionReactionSeverity severity;

    @Column(name = "onset_at", nullable = false)
    private LocalDateTime onsetAt;

    @Size(max = 1000)
    @Column(name = "signs_symptoms", nullable = false, length = 1000)
    private String signsSymptoms;

    @Size(max = 1000)
    @Column(name = "actions_taken", length = 1000)
    private String actionsTaken;

    @Column(name = "unit_returned_to_lab", nullable = false)
    @Builder.Default
    private Boolean unitReturnedToLab = Boolean.FALSE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_reaction_reporter"))
    private Staff reportedBy;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    /**
     * Reactions that make the implicated unit evidence rather than stock, and
     * that warrant an immediate clinical escalation.
     */
    public boolean isSevere() {
        return severity == TransfusionReactionSeverity.SEVERE
            || severity == TransfusionReactionSeverity.LIFE_THREATENING
            || reactionType == TransfusionReactionType.ACUTE_HEMOLYTIC
            || reactionType == TransfusionReactionType.ANAPHYLACTIC
            || reactionType == TransfusionReactionType.TRALI
            || reactionType == TransfusionReactionType.SEPTIC;
    }
}
