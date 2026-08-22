package com.example.hms.model;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
import java.util.UUID;

/**
 * One microbiology culture report on a lab order (P3 #19).
 *
 * <p>The first micro-shaped surface: a {@link LabResult} is one opaque
 * result_value string with no analyte identity, so organism identification
 * plus a susceptibility panel per organism was unrepresentable. Isolates
 * ({@link MicroIsolate}) hang off this report; susceptibilities hang off
 * each isolate.
 *
 * <p>patient/hospital are denormalized rather than reached through the
 * order: lab_results has no hospital column and its tenancy-by-join left
 * acknowledge/read-back with a cross-tenant hole — scoping here stays a
 * column scan the 404-not-403 idiom can use directly.
 */
@Entity
@Table(
    name = "micro_culture_results",
    schema = "lab",
    indexes = {
        @Index(name = "idx_micro_culture_order",    columnList = "lab_order_id"),
        @Index(name = "idx_micro_culture_patient",  columnList = "patient_id"),
        @Index(name = "idx_micro_culture_hospital", columnList = "hospital_id"),
        @Index(name = "idx_micro_culture_status",   columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"labOrder", "specimen", "patient", "hospital", "reportedByStaff", "documentedBy"})
public class MicroCultureResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_culture_order"))
    private LabOrder labOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specimen_id",
        foreignKey = @ForeignKey(name = "fk_micro_culture_specimen"))
    private LabSpecimen specimen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_culture_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_micro_culture_hospital"))
    private Hospital hospital;

    /* ── Specimen context ──────────────────────────────────────────────── */

    /** Body site / sample description, e.g. "Blood — peripheral, left arm". */
    @Size(max = 100)
    @Column(name = "specimen_source", length = 100)
    private String specimenSource;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    /* ── Report ────────────────────────────────────────────────────────── */

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MicroCultureStatus status = MicroCultureStatus.PRELIMINARY;

    /** Null while the culture is still pending; required to finalize. */
    @Enumerated(EnumType.STRING)
    @Column(name = "growth_result", length = 20)
    private MicroGrowthResult growthResult;

    @Size(max = 255)
    @Column(name = "gram_stain", length = 255)
    private String gramStain;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "finalized_by_user_id")
    private UUID finalizedByUserId;

    @Column(name = "corrected_at")
    private LocalDateTime correctedAt;

    /** Mandatory on any mutation after FINAL — the report never silently reverts. */
    @Size(max = 500)
    @Column(name = "correction_reason", length = 500)
    private String correctionReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /* ── Attribution ───────────────────────────────────────────────────── */

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_micro_culture_staff"))
    private Staff reportedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_micro_culture_user"))
    private User documentedBy;

    /** A finalized-or-corrected report refuses silent edits. */
    public boolean isLocked() {
        return status != MicroCultureStatus.PRELIMINARY;
    }

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (status == null) {
            status = MicroCultureStatus.PRELIMINARY;
        }
        if (notes != null) {
            notes = notes.trim();
        }
    }
}
