package com.example.hms.model.encounter;

import com.example.hms.enums.EncounterNoteLinkType;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "encounter_note_links",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_encounter_note_link", columnNames = {"note_id", "artifact_id", "artifact_type"})
    }
)
@EqualsAndHashCode(callSuper = true, exclude = "note")
@ToString(exclude = "note")
public class EncounterNoteLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_link_note"))
    private EncounterNote note;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 30)
    private EncounterNoteLinkType artifactType;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "artifact_code", length = 120)
    private String artifactCode;

    @Column(name = "artifact_display", length = 255)
    private String artifactDisplay;

    @Column(name = "artifact_status", length = 120)
    private String artifactStatus;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    @PrePersist
    private void defaultLinkedAt() {
        if (linkedAt == null) {
            linkedAt = LocalDateTime.now();
        }
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof EncounterNoteLink;
    }
}
