package com.example.hms.model.encounter;

import com.example.hms.enums.EncounterNoteTemplate;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "encounter_notes",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_encounter_notes_patient", columnList = "patient_id"),
        @Index(name = "idx_encounter_notes_hospital", columnList = "hospital_id"),
        @Index(name = "idx_encounter_notes_documented", columnList = "documented_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_encounter_note_encounter", columnNames = "encounter_id")
    }
)
@EqualsAndHashCode(callSuper = true, exclude = {"encounter", "patient", "hospital", "author", "authorStaff", "addenda", "links"})
@ToString(exclude = {"encounter", "patient", "hospital", "author", "authorStaff", "addenda", "links"})
public class EncounterNote extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_author_user"))
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_staff_id", foreignKey = @ForeignKey(name = "fk_encounter_note_author_staff"))
    private Staff authorStaff;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "author_credentials", length = 200)
    private String authorCredentials;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "template", nullable = false, length = 12)
    @Builder.Default
    private EncounterNoteTemplate template = EncounterNoteTemplate.SOAP;

    @Column(name = "chief_complaint", columnDefinition = "TEXT")
    private String chiefComplaint;

    @Column(name = "history_present_illness", columnDefinition = "TEXT")
    private String historyOfPresentIllness;

    @Column(name = "review_of_systems", columnDefinition = "TEXT")
    private String reviewOfSystems;

    @Column(name = "physical_exam", columnDefinition = "TEXT")
    private String physicalExam;

    @Column(name = "diagnostic_results", columnDefinition = "TEXT")
    private String diagnosticResults;

    @Column(name = "data_subjective", columnDefinition = "TEXT")
    private String subjective;

    @Column(name = "data_objective", columnDefinition = "TEXT")
    private String objective;

    @Column(name = "data_assessment", columnDefinition = "TEXT")
    private String assessment;

    @Column(name = "data_plan", columnDefinition = "TEXT")
    private String plan;

    @Column(name = "data_implementation", columnDefinition = "TEXT")
    private String implementation;

    @Column(name = "data_evaluation", columnDefinition = "TEXT")
    private String evaluation;

    @Column(name = "patient_instructions", columnDefinition = "TEXT")
    private String patientInstructions;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "late_entry", nullable = false)
    @Builder.Default
    private boolean lateEntry = false;

    @Column(name = "event_occurred_at")
    private LocalDateTime eventOccurredAt;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Builder.Default
    @Column(name = "attest_accuracy", nullable = false)
    private boolean attestAccuracy = false;

    @Builder.Default
    @Column(name = "attest_no_abbreviations", nullable = false)
    private boolean attestNoAbbreviations = false;

    @Builder.Default
    @Column(name = "attest_spell_check", nullable = false)
    private boolean attestSpellCheck = false;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by_name", length = 200)
    private String signedByName;

    @Column(name = "signed_by_credentials", length = 200)
    private String signedByCredentials;

    @Builder.Default
    @OneToMany(mappedBy = "note", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("documentedAt ASC")
    private List<EncounterNoteAddendum> addenda = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "note", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linkedAt ASC")
    private List<EncounterNoteLink> links = new ArrayList<>();

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
        if (patient != null && !Objects.equals(patient, encounter.getPatient())) {
            throw new IllegalStateException("EncounterNote.patient must match Encounter.patient");
        }
        if (hospital != null && !Objects.equals(hospital, encounter.getHospital())) {
            throw new IllegalStateException("EncounterNote.hospital must match Encounter.hospital");
        }
    }

    public void addAddendum(EncounterNoteAddendum addendum) {
        if (addendum == null) {
            return;
        }
        addenda.add(addendum);
        addendum.setNote(this);
    }

    public List<EncounterNoteLink> getLinks() {
        return List.copyOf(links);
    }

    public void addLink(EncounterNoteLink link) {
        if (link == null) {
            return;
        }
        links.add(link);
        link.setNote(this);
    }

    public void clearLinks() {
        if (links == null) {
            links = new ArrayList<>();
        } else {
            links.clear();
        }
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof EncounterNote;
    }
}
