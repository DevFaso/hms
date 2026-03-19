package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

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
        @Index(name = "idx_patient_diagnoses_patient_status", columnList = "patient_id, status")
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
}
