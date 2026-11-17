package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A structured, persistable patient diagnosis record.
 * Allows storing active/historical diagnoses per patient with ICD codes.
 */
@Entity
@Table(
    name = "patient_diagnoses",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_patient_diagnoses_patient", columnList = "patient_id"),
        @Index(name = "idx_patient_diagnoses_patient_status", columnList = "patient_id, status"),
        @Index(name = "idx_patient_diagnoses_patient_hospital", columnList = "patient_id, hospital_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PatientDiagnosis extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagnosed_by")
    private Staff diagnosedBy;

    /**
     * The hospital this diagnosis was recorded at (V171), which is what a
     * chart read is scoped by. V14 created the table without one, so every
     * row written before V171 has none: such a row reaches a staff reader
     * only when that reader is a verified super-admin, and the patient on
     * their own record. It is never derived from {@link #diagnosedBy}: the
     * diagnosing staff's hospital is a property of the subject, and a read's
     * scope is the caller's. A NEW row must carry it — see
     * {@link #requireHospitalOnCreate()}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_patient_diagnoses_hospital"))
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Hospital hospital;

    @Column(length = 20)
    private String icdCode;

    @Column(nullable = false, length = 500)
    private String description;

    /** ACTIVE | RESOLVED | CHRONIC */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime diagnosedAt = OffsetDateTime.now(ZoneOffset.UTC);

    /**
     * Nothing in the application writes this table today — it is read-only
     * legacy — so this is the invariant for whatever writes it next: a
     * diagnosis recorded without its hospital would be readable only by a
     * super-admin, i.e. invisible to the clinicians it was written for, so
     * it is refused at insert rather than silently stored unscoped.
     */
    @PrePersist
    void requireHospitalOnCreate() {
        if (hospital == null) {
            throw new IllegalStateException(
                "A new patient diagnosis must record the hospital it was made at (V171).");
        }
    }
}
