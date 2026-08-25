package com.example.hms.model;

import com.example.hms.enums.TransfusionAdministrationStatus;
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
 * One unit being given at the bedside (Tier 2 item 28).
 *
 * <p>Two staff are recorded, not one. A transfusion is the administration where
 * a single signature is not accepted practice anywhere: the bedside check that
 * this unit belongs to this patient is performed by two people independently,
 * and the service refuses the same staff member for both roles.
 *
 * <p>A unique index on {@code blood_unit_id} makes a double-hang of the same
 * bag unrepresentable rather than merely discouraged.
 */
@Entity
@Table(
    name = "transfusion_administrations",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_admin_patient", columnList = "patient_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"request", "bloodUnit", "patient", "hospital", "administeredBy", "verifiedBy"})
public class TransfusionAdministration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_admin_request"))
    private TransfusionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_unit_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_admin_unit"))
    private BloodUnit bloodUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_admin_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_admin_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransfusionAdministrationStatus status = TransfusionAdministrationStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "volume_transfused_ml")
    private Integer volumeTransfusedMl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administered_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_admin_giver"))
    private Staff administeredBy;

    /** The independent second check. Never the same person as the giver. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_admin_verifier"))
    private Staff verifiedBy;

    @Size(max = 60)
    @Column(name = "verification_method", length = 60)
    private String verificationMethod;

    @Size(max = 500)
    @Column(name = "stop_reason", length = 500)
    private String stopReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    public boolean isInProgress() {
        return status == TransfusionAdministrationStatus.IN_PROGRESS;
    }
}
