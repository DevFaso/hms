package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "patient_insurances",
    schema = "clinical",
    uniqueConstraints = {
        // A patient shouldn't have the exact same policy duplicated
        @UniqueConstraint(name = "uq_insurance_patient_provider_policy",
            columnNames = {"patient_id", "provider_name", "policy_number"})
    },
    indexes = {
        @Index(name = "idx_pi_patient", columnList = "patient_id"),
        @Index(name = "idx_pi_assignment", columnList = "assignment_id"),
        @Index(name = "idx_pi_effective", columnList = "effective_date"),
        @Index(name = "idx_pi_expiration", columnList = "expiration_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "assignment"})
@EqualsAndHashCode(callSuper = true)
public class PatientInsurance extends BaseEntity {
    @Size(max = 50)
    @Column(name = "payer_code", length = 50)
    private String payerCode;

    /** Patient who owns this insurance (nullable for delayed linking). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id",
        foreignKey = @ForeignKey(name = "fk_pi_patient"))
    private Patient patient;

    /** Hospital/role assignment context (nullable; for RBAC/auditing). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id",
        foreignKey = @ForeignKey(name = "fk_pi_assignment"))
    private UserRoleHospitalAssignment assignment;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @NotBlank
    @Size(max = 150)
    @Column(name = "provider_name", nullable = false, length = 150)
    private String providerName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "policy_number", nullable = false, length = 100)
    private String policyNumber;

    @Size(max = 100)
    @Column(name = "group_number", length = 100)
    private String groupNumber;

    @Size(max = 150)
    @Column(name = "subscriber_name", length = 150)
    private String subscriberName;

    @Size(max = 50)
    @Column(name = "subscriber_relationship", length = 50)
    private String subscriberRelationship;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /** Who linked this insurance to the patient (optional auditing). */
    @Column(name = "linked_by_user_id")
    private UUID linkedByUserId;

    /** How it was linked (e.g., SELF, STAFF, IMPORT). */
    @Size(max = 10)
    @Column(name = "linked_as", length = 10)
    private String linkedAs;

    @PrePersist
    @PreUpdate
    private void validateAndNormalize() {
        if (providerName != null) providerName = providerName.trim();
        if (policyNumber != null) policyNumber = policyNumber.trim();
        if (groupNumber != null) groupNumber = groupNumber.trim();
        if (subscriberName != null) subscriberName = subscriberName.trim();
        if (linkedAs != null) linkedAs = linkedAs.trim().toUpperCase();

        if (effectiveDate != null && expirationDate != null && expirationDate.isBefore(effectiveDate)) {
            throw new IllegalStateException("expirationDate cannot be before effectiveDate");
        }
    }
}
