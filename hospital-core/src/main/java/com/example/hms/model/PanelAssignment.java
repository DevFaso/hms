package com.example.hms.model;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

import java.time.LocalDate;

/**
 * One empanelment: one patient linked to one panel owner — a primary
 * provider or a community health worker — at one hospital (Tier 2 item 37).
 *
 * <p>Uniqueness is one <b>ACTIVE</b> assignment per (patient, hospital,
 * panelRole), enforced by V149's partial unique index. Reassignment ENDs the
 * old row and creates a new ACTIVE one — the ENDED rows underneath are the
 * answer to "who was responsible for this patient in March".
 */
@Entity
@Table(
    name = "panel_assignments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_panel_patient",       columnList = "patient_id"),
        @Index(name = "idx_panel_provider",      columnList = "provider_staff_id, status"),
        @Index(name = "idx_panel_hospital_role", columnList = "hospital_id, panel_role, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "providerStaff", "assignedBy"})
public class PanelAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_panel_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_panel_hospital"))
    private Hospital hospital;

    /** The panel owner: the staff row of the provider or CHW. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_panel_provider"))
    private Staff providerStaff;

    @Enumerated(EnumType.STRING)
    @Column(name = "panel_role", nullable = false, length = 20)
    private PanelRole panelRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PanelAssignmentStatus status = PanelAssignmentStatus.ACTIVE;

    /** May predate the row for backfilled paper panels — same stance as V146. */
    @Column(name = "assigned_on", nullable = false)
    private LocalDate assignedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_panel_assigner"))
    private Staff assignedBy;

    /** Set when the assignment leaves ACTIVE. */
    @Column(name = "ended_on")
    private LocalDate endedOn;

    /**
     * Optimistic lock. Two concurrent end/supersede transitions on the same
     * row must not both succeed — the second flush fails and the service
     * translates it into a clean "reload and retry" refusal.
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Patient-specific narrative, so encrypted at rest; TEXT because AES-GCM+Base64 outgrows the plaintext cap. */
    @Size(max = 500)
    @Column(name = "end_reason", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String endReason;
}
