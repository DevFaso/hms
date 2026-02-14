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
 * Persists individual vital sign capture events for a patient. Each record represents a single
 * measurement bundle (heart rate, blood pressure, etc.) entered by clinical staff or an automated source.
 */
@Entity
@Table(
    name = "patient_vital_signs",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_patient_vitals_patient", columnList = "patient_id"),
        @Index(name = "idx_patient_vitals_recorded_at", columnList = "recorded_at"),
        @Index(name = "idx_patient_vitals_registration", columnList = "registration_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"patient", "registration", "hospital", "recordedByStaff", "recordedByAssignment"})
public class PatientVitalSign extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_patient_vitals_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id",
        foreignKey = @ForeignKey(name = "fk_patient_vitals_registration"))
    private PatientHospitalRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_patient_vitals_hospital"))
    private Hospital hospital;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_patient_vitals_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_assignment_id",
        foreignKey = @ForeignKey(name = "fk_patient_vitals_assignment"))
    private UserRoleHospitalAssignment recordedByAssignment;

    @Size(max = 40)
    @Column(name = "source", length = 40)
    private String source;

    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    @Column(name = "heart_rate_bpm")
    private Integer heartRateBpm;

    @Column(name = "respiratory_rate_bpm")
    private Integer respiratoryRateBpm;

    @Column(name = "systolic_bp_mm_hg")
    private Integer systolicBpMmHg;

    @Column(name = "diastolic_bp_mm_hg")
    private Integer diastolicBpMmHg;

    @Column(name = "spo2_percent")
    private Integer spo2Percent;

    @Column(name = "blood_glucose_mg_dl")
    private Integer bloodGlucoseMgDl;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Size(max = 40)
    @Column(name = "body_position", length = 40)
    private String bodyPosition;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @Builder.Default
    @Column(name = "clinically_significant", nullable = false)
    private boolean clinicallySignificant = false;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
        if (source != null) {
            source = source.trim();
        }
        if (bodyPosition != null) {
            bodyPosition = bodyPosition.trim();
        }
        if (notes != null) {
            notes = notes.trim();
        }
    }
}
