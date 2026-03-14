package com.example.hms.model.medication;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "medication_administration_records",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_mar_patient", columnList = "patient_id"),
        @Index(name = "idx_mar_hospital", columnList = "hospital_id"),
        @Index(name = "idx_mar_nurse", columnList = "nurse_id"),
        @Index(name = "idx_mar_status", columnList = "status"),
        @Index(name = "idx_mar_scheduled_time", columnList = "scheduled_time"),
        @Index(name = "idx_mar_prescription", columnList = "prescription_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"prescription", "patient", "nurse", "hospital"})
public class MedicationAdministrationRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id",
        foreignKey = @ForeignKey(name = "fk_mar_prescription"))
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mar_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nurse_id",
        foreignKey = @ForeignKey(name = "fk_mar_nurse"))
    private Staff nurse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mar_hospital"))
    private Hospital hospital;

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

    @Column(name = "scheduled_time", nullable = false)
    private LocalDateTime scheduledTime;

    @Column(name = "administered_at")
    private LocalDateTime administeredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MedicationAdministrationStatus status = MedicationAdministrationStatus.PENDING;

    @Size(max = 1024)
    @Column(name = "hold_reason", length = 1024)
    private String holdReason;

    @Size(max = 1024)
    @Column(name = "refusal_reason", length = 1024)
    private String refusalReason;

    @Size(max = 1024)
    @Column(name = "note", length = 1024)
    private String note;
}
