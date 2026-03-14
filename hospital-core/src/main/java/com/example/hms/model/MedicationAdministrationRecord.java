package com.example.hms.model;

import com.example.hms.enums.MedicationAdministrationStatus;
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
import jakarta.validation.constraints.NotBlank;
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
 * Tracks a single medication administration event linked to a {@link Prescription}.
 * Created when a nurse administers, holds, refuses, or misses a scheduled medication dose.
 */
@Entity
@Table(
    name = "medication_administration_records",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_mar_prescription", columnList = "prescription_id"),
        @Index(name = "idx_mar_patient", columnList = "patient_id"),
        @Index(name = "idx_mar_hospital", columnList = "hospital_id"),
        @Index(name = "idx_mar_nurse", columnList = "administered_by_staff_id"),
        @Index(name = "idx_mar_scheduled", columnList = "scheduled_time"),
        @Index(name = "idx_mar_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"prescription", "patient", "hospital", "administeredByStaff"})
public class MedicationAdministrationRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mar_prescription"))
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mar_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mar_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administered_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_mar_staff"))
    private Staff administeredByStaff;

    /** Denormalized medication name for quick display without joining Prescription. */
    @NotBlank
    @Size(max = 255)
    @Column(name = "medication_name", nullable = false, length = 255)
    private String medicationName;

    @Size(max = 100)
    @Column(name = "dose", length = 100)
    private String dose;

    @Size(max = 80)
    @Column(name = "route", length = 80)
    private String route;

    /** When this dose was originally scheduled. */
    @NotNull
    @Column(name = "scheduled_time", nullable = false)
    private LocalDateTime scheduledTime;

    /** When the action actually occurred (null if still PENDING). */
    @Column(name = "administered_at")
    private LocalDateTime administeredAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MedicationAdministrationStatus status = MedicationAdministrationStatus.PENDING;

    /** Reason required when status is HELD or REFUSED. */
    @Size(max = 1024)
    @Column(name = "reason", length = 1024)
    private String reason;

    /** Free-text note from the nurse. */
    @Size(max = 2000)
    @Column(name = "notes", length = 2000)
    private String notes;
}
