package com.example.hms.model.encounter;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "encounter_note_addenda", schema = "clinical")
@EqualsAndHashCode(callSuper = true, exclude = {"note", "author", "authorStaff"})
@ToString(exclude = {"note", "author", "authorStaff"})
public class EncounterNoteAddendum extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_addendum_note"))
    private EncounterNote note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_encounter_note_addendum_author_user"))
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_staff_id", foreignKey = @ForeignKey(name = "fk_encounter_note_addendum_author_staff"))
    private Staff authorStaff;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "author_credentials", length = 200)
    private String authorCredentials;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "event_occurred_at")
    private LocalDateTime eventOccurredAt;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "attest_accuracy", nullable = false)
    private boolean attestAccuracy;

    @Column(name = "attest_no_abbreviations", nullable = false)
    private boolean attestNoAbbreviations;

    @PrePersist
    private void defaultDocumentedAt() {
        if (documentedAt == null) {
            documentedAt = LocalDateTime.now();
        }
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof EncounterNoteAddendum;
    }
}
