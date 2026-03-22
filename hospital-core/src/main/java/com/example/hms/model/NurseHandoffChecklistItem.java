package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
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
 * A single checklist item within a {@link NurseHandoff}.
 * Tracks whether it has been reviewed/completed and by whom.
 */
@Entity
@Table(
    name = "nurse_handoff_checklist_items",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_handoff_item_handoff", columnList = "handoff_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"handoff"})
public class NurseHandoffChecklistItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handoff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_checklist_handoff"))
    private NurseHandoff handoff;

    /** Human-readable description of the checklist item. */
    @NotBlank
    @Size(max = 500)
    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /** Display order within the checklist. */
    @Column(name = "sort_order")
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "completed")
    @Builder.Default
    private boolean completed = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Denormalized name of the nurse who completed this item. */
    @Size(max = 200)
    @Column(name = "completed_by_name", length = 200)
    private String completedByName;
}
