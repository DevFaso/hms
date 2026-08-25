package com.example.hms.model;

import com.example.hms.enums.TransferOrderStatus;
import com.example.hms.enums.TransferType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/**
 * An in-app order to move a patient between beds or wards (Tier 2 item 30).
 *
 * <p>This records the DECISION to move. The move itself still goes through
 * {@code BedAssignmentService}, which has owned the
 * {@code Admission.bed} ↔ {@code Bed.status} invariant since P0 #4 — so there
 * remains exactly one writer of that invariant and this is an orchestration
 * layer above it, not a second one.
 *
 * <p><b>{@link #fromBed} and {@link #fromWard} are a snapshot.</b> They record
 * where the patient actually was when the order was raised. Reading the origin
 * back off the admission later would report where they are NOW, which after a
 * completed transfer is the destination — so "where did they come from" would
 * confidently answer itself wrong.
 */
@Entity
@Table(
    name = "transfer_orders",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_transfer_by_admission", columnList = "admission_id, requested_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"hospital", "admission", "patient", "fromBed", "toBed", "fromWard", "toWard",
    "requestedBy", "completedBy", "cancelledBy"})
public class TransferOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfer_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admission_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfer_admission"))
    private Admission admission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfer_patient"))
    private Patient patient;

    /** Where the patient was at order time. Null when they held no bed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_bed_id",
        foreignKey = @ForeignKey(name = "fk_transfer_from_bed"))
    private Bed fromBed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_ward_id",
        foreignKey = @ForeignKey(name = "fk_transfer_from_ward"))
    private Ward fromWard;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_bed_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfer_to_bed"))
    private Bed toBed;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_ward_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_transfer_to_ward"))
    private Ward toWard;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 20)
    private TransferType transferType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferOrderStatus status;

    @NotNull
    @Size(max = 500)
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_transfer_requested_by"))
    private Staff requestedBy;

    @NotNull
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_transfer_completed_by"))
    private Staff completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_transfer_cancelled_by"))
    private Staff cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Size(max = 500)
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Set when the destination could not contain an active airborne
     * precaution and a clinician moved the patient anyway. Recorded rather
     * than prevented: refusing outright pushes the decision outside the
     * system, where nothing captures it at all.
     */
    @Column(name = "isolation_override", nullable = false)
    @Builder.Default
    private boolean isolationOverride = false;

    @Size(max = 500)
    @Column(name = "isolation_override_reason", length = 500)
    private String isolationOverrideReason;

    /** Still waiting to be carried out — the destination is held for it. */
    public boolean isPending() {
        return status == TransferOrderStatus.REQUESTED;
    }
}
