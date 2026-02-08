package com.example.hms.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

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

    /** Context (role@hospital) of the user posting the result. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_labresult_assignment"))
    private UserRoleHospitalAssignment assignment;

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

        if (labOrder == null || assignment == null
            || labOrder.getHospital() == null || assignment.getHospital() == null
            || !Objects.equals(labOrder.getHospital().getId(), assignment.getHospital().getId())) {
            throw new IllegalStateException("LabResult.assignment.hospital must match LabResult.labOrder.hospital");
        }
    }
}
