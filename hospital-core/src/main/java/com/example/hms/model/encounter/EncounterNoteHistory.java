package com.example.hms.model.encounter;

import com.example.hms.enums.EncounterNoteTemplate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "encounter_note_history", schema = "clinical")
public class EncounterNoteHistory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "note_id")
    private UUID noteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "template", length = 12)
    private EncounterNoteTemplate template;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "changed_by", length = 120)
    private String changedBy;

    @Column(name = "change_type", length = 30)
    private String changeType;

    @Column(name = "content_snapshot", columnDefinition = "TEXT")
    private String contentSnapshot;

    @Column(name = "metadata_snapshot", columnDefinition = "TEXT")
    private String metadataSnapshot;
}
