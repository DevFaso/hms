package com.example.hms.model;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "lab_results",
    schema = "lab",
    indexes = {
        @Index(name = "idx_lab_result_order", columnList = "lab_order_id"),
        @Index(name = "idx_lab_result_date", columnList = "result_date"),
        @Index(name = "idx_lab_result_assignment", columnList = "assignment_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"labOrder", "assignment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_labresult_order"))
    private LabOrder labOrder;

    @NotBlank
    @Column(name = "result_value", nullable = false, length = 2048)
    private String resultValue;

    @Column(name = "result_unit", length = 50)
    private String resultUnit;

    @NotNull
    @Column(name = "result_date", nullable = false)
    private LocalDateTime resultDate;

    @Column(name = "notes", length = 2048)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "abnormal_flag", length = 20)
    private AbnormalFlag abnormalFlag;

    /**
     * Context (role@hospital) of the user posting the result. Required
     * for USER-actor writes (the existing clinical path); always
     * {@code null} for SYSTEM-actor writes coming through the MLLP /
     * external-LIS ingestion path. The DB-level CHECK constraint
     * (chk_labresult_user_or_system, V61) enforces the same invariant.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id",
        foreignKey = @ForeignKey(name = "fk_labresult_assignment"))
    private UserRoleHospitalAssignment assignment;

    /**
     * Identifies whether this result was posted by a real user or by a
     * system/integration process. Drives the @PrePersist guards and the
     * CHECK constraint introduced in V61. Defaults to USER on legacy
     * rows so the existing clinical write path remains identical.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    @Builder.Default
    private ActorType actorType = ActorType.USER;

    /**
     * Human-readable origin label for SYSTEM-actor writes (e.g.
     * {@code "MLLP:ROCHE_COBAS/LAB_A"}). Optional for USER writes.
     */
    @Size(max = 255)
    @Column(name = "actor_label", length = 255)
    private String actorLabel;

    @Builder.Default
    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by_user_id")
    private UUID acknowledgedByUserId;

    @Column(name = "acknowledged_by_display", length = 255)
    private String acknowledgedByDisplay;

    @Builder.Default
    @Column(name = "released", nullable = false)
    private boolean released = false;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "released_by_user_id")
    private UUID releasedByUserId;

    @Column(name = "released_by_display", length = 255)
    private String releasedByDisplay;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by_user_id")
    private UUID signedByUserId;

    @Column(name = "signed_by_display", length = 255)
    private String signedByDisplay;

    @Column(name = "signature_value", length = 2048)
    private String signatureValue;

    @Column(name = "signature_notes", length = 2048)
    private String signatureNotes;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (resultDate == null) resultDate = LocalDateTime.now();
        if (actorType == null) {
            actorType = (assignment == null) ? ActorType.SYSTEM : ActorType.USER;
        }

        if (labOrder == null || labOrder.getHospital() == null) {
            throw new IllegalStateException("LabResult.labOrder.hospital must be present");
        }

        if (actorType == ActorType.USER) {
            if (assignment == null || assignment.getHospital() == null
                || !Objects.equals(labOrder.getHospital().getId(), assignment.getHospital().getId())) {
                throw new IllegalStateException(
                    "LabResult.assignment.hospital must match LabResult.labOrder.hospital for USER writes");
            }
        } else {
            // SYSTEM writes (MLLP / external LIS): no human assignment is
            // available. The CHECK constraint in V61 enforces the same
            // shape at the DB level.
            if (assignment != null) {
                throw new IllegalStateException(
                    "LabResult.assignment must be null for SYSTEM writes; use actorLabel for traceability");
            }
        }
    }
}
