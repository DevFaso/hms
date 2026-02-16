package com.example.hms.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured nursing documentation captured from the dashboard workflow. Notes are immutable after
 * creation with the exception of additive addenda that preserve the original entry for legal auditability.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "nursing_notes",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_nursing_notes_patient", columnList = "patient_id"),
        @Index(name = "idx_nursing_notes_hospital", columnList = "hospital_id"),
        @Index(name = "idx_nursing_notes_created", columnList = "created_at")
    }
)
@EqualsAndHashCode(callSuper = true, exclude = {"patient", "hospital", "author", "authorStaff", "addenda"})
@ToString(exclude = {"patient", "hospital", "author", "authorStaff", "addenda"})
public class NursingNote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, foreignKey = @ForeignKey(name = "fk_nursing_notes_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false, foreignKey = @ForeignKey(name = "fk_nursing_notes_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_nursing_notes_author_user"))
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_staff_id", foreignKey = @ForeignKey(name = "fk_nursing_notes_author_staff"))
    private Staff authorStaff;

    @Size(max = 200)
    @Column(name = "author_name", length = 200)
    private String authorName;

    @Size(max = 200)
    @Column(name = "author_credentials", length = 200)
    private String authorCredentials;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "template", nullable = false, length = 12)
    private NursingNoteTemplate template;

    @Column(name = "data_subjective", columnDefinition = "TEXT")
    private String dataSubjective;

    @Column(name = "data_objective", columnDefinition = "TEXT")
    private String dataObjective;

    @Column(name = "data_assessment", columnDefinition = "TEXT")
    private String dataAssessment;

    @Column(name = "data_plan", columnDefinition = "TEXT")
    private String dataPlan;

    @Column(name = "data_implementation", columnDefinition = "TEXT")
    private String dataImplementation;

    @Column(name = "data_evaluation", columnDefinition = "TEXT")
    private String dataEvaluation;

    @Column(name = "action_summary", columnDefinition = "TEXT")
    private String actionSummary;

    @Column(name = "response_summary", columnDefinition = "TEXT")
    private String responseSummary;

    @Column(name = "education_summary", columnDefinition = "TEXT")
    private String educationSummary;

    @Column(name = "narrative", columnDefinition = "TEXT")
    private String narrative;

    @Builder.Default
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry = false;

    @Column(name = "event_occurred_at")
    private LocalDateTime eventOccurredAt;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Builder.Default
    @Column(name = "attest_accuracy", nullable = false)
    private boolean attestAccuracy = false;

    @Builder.Default
    @Column(name = "attest_spell_check", nullable = false)
    private boolean attestSpellCheck = false;

    @Builder.Default
    @Column(name = "attest_no_abbreviations", nullable = false)
    private boolean attestNoAbbreviations = false;

    @Column(name = "readability_score")
    private Double readabilityScore;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Size(max = 200)
    @Column(name = "signed_by_name", length = 200)
    private String signedByName;

    @Size(max = 200)
    @Column(name = "signed_by_credentials", length = 200)
    private String signedByCredentials;

    @Builder.Default
    @ElementCollection
    @CollectionTable(
        name = "nursing_note_education_entries",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "note_id", nullable = false, foreignKey = @ForeignKey(name = "fk_note_education_note"))
    )
    @OrderColumn(name = "entry_order")
    private List<NursingNoteEducationEntry> educationEntries = new ArrayList<>();

    @Builder.Default
    @ElementCollection
    @CollectionTable(
        name = "nursing_note_interventions",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "note_id", nullable = false, foreignKey = @ForeignKey(name = "fk_note_intervention_note"))
    )
    @OrderColumn(name = "entry_order")
    private List<NursingNoteInterventionEntry> interventionEntries = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "note", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("documentedAt ASC")
    private List<NursingNoteAddendum> addenda = new ArrayList<>();

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (documentedAt == null) {
            documentedAt = LocalDateTime.now();
        }
        if (authorName != null) {
            authorName = authorName.trim();
        }
        if (authorCredentials != null) {
            authorCredentials = authorCredentials.trim();
        }
        if (signedByName != null) {
            signedByName = signedByName.trim();
        }
        if (signedByCredentials != null) {
            signedByCredentials = signedByCredentials.trim();
        }
    }

    public void addEducationEntry(NursingNoteEducationEntry entry) {
        if (entry == null) {
            return;
        }
        educationEntries.add(entry);
    }

    public void addInterventionEntry(NursingNoteInterventionEntry entry) {
        if (entry == null) {
            return;
        }
        interventionEntries.add(entry);
    }

    public void addAddendum(NursingNoteAddendum addendum) {
        if (addendum == null) {
            return;
        }
        addenda.add(addendum);
        addendum.setNote(this);
    }
}
