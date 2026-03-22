package com.example.hms.model;

import com.example.hms.enums.NurseHandoffStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
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
 * A nurse-to-nurse handoff report for a patient, typically at shift change.
 * Contains a direction description, free-text note, and a checklist of items
 * that must be reviewed before the handoff is considered complete.
 */
@Entity
@Table(
    name = "nurse_handoffs",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_handoff_patient", columnList = "patient_id"),
        @Index(name = "idx_handoff_hospital", columnList = "hospital_id"),
        @Index(name = "idx_handoff_status", columnList = "status"),
        @Index(name = "idx_handoff_created_by", columnList = "created_by_staff_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "createdByStaff", "completedByStaff", "checklistItems"})
public class NurseHandoff extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_handoff_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_handoff_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_handoff_creator"))
    private Staff createdByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_handoff_completer"))
    private Staff completedByStaff;

    /** E.g. "Transfer to Radiology", "Shift Change Day→Night", "Return from OR". */
    @NotBlank
    @Size(max = 255)
    @Column(name = "direction", nullable = false, length = 255)
    private String direction;

    /** Free-text handoff note with clinical context. */
    @Size(max = 4000)
    @Column(name = "note", length = 4000)
    private String note;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private NurseHandoffStatus status = NurseHandoffStatus.PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "handoff", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<NurseHandoffChecklistItem> checklistItems = new ArrayList<>();
}
