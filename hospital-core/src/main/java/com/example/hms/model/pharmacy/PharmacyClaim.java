package com.example.hms.model.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Insurance claim linked to a dispense event.
 * Tracks AMU / insurer claim lifecycle from draft to payment.
 */
@Entity
@Table(
    name = "pharmacy_claims",
    schema = "billing",
    indexes = {
        @Index(name = "idx_pc_dispense", columnList = "dispense_id"),
        @Index(name = "idx_pc_patient", columnList = "patient_id"),
        @Index(name = "idx_pc_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pc_status", columnList = "claim_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"dispense", "patient", "hospital", "submittedByUser"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyClaim extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispense_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pc_dispense"))
    private Dispense dispense;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pc_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pc_hospital"))
    private Hospital hospital;

    @Size(max = 255)
    @Column(name = "coverage_reference", length = 255)
    private String coverageReference;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "claim_status", nullable = false, length = 30)
    @Builder.Default
    private PharmacyClaimStatus claimStatus = PharmacyClaimStatus.DRAFT;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Size(max = 10)
    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "XOF";

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by",
        foreignKey = @ForeignKey(name = "fk_pc_submitted"))
    private User submittedByUser;

    @Size(max = 1000)
    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;
}
