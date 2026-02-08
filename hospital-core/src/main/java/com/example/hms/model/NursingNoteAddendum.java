package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Immutable audit trail entries appended to a nursing note when amendments are required.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "nursing_note_addenda", schema = "clinical")
@EqualsAndHashCode(callSuper = true, exclude = {"note", "author", "authorStaff"})
@ToString(exclude = {"note", "author", "authorStaff"})
public class NursingNoteAddendum extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false, foreignKey = @ForeignKey(name = "fk_note_addendum_note"))
    private NursingNote note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_note_addendum_author_user"))
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_staff_id", foreignKey = @ForeignKey(name = "fk_note_addendum_author_staff"))
    private Staff authorStaff;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "author_credentials", length = 200)
    private String authorCredentials;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
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
}
