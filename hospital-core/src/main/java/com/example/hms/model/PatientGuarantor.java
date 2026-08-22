package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A financially responsible party for a patient (P3 #21). The first
 * guarantor concept in the system — until V126 the only
 * payer-other-than-patient data was the insurance subscriber pair.
 * Deactivate-never-delete; linking guarantors onto billing invoices is a
 * billing-workflow decision deliberately not made here.
 */
@Entity
@Table(
    name = "patient_guarantors",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_guarantor_patient",  columnList = "patient_id"),
        @Index(name = "idx_guarantor_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital"})
public class PatientGuarantor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_guarantor_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_guarantor_hospital"))
    private Hospital hospital;

    @Size(max = 200)
    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Size(max = 50)
    @Column(name = "relationship", length = 50)
    private String relationship;

    @Size(max = 20)
    @Column(name = "phone", length = 20)
    private String phone;

    @Size(max = 150)
    @Column(name = "email", length = 150)
    private String email;

    @Size(max = 255)
    @Column(name = "address", length = 255)
    private String address;

    @Builder.Default
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;
}
