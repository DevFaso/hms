package com.example.hms.model;

import com.example.hms.enums.DischargeStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "discharge_approvals",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_discharge_patient", columnList = "patient_id"),
        @Index(name = "idx_discharge_hospital", columnList = "hospital_id"),
        @Index(name = "idx_discharge_status", columnList = "status"),
        @Index(name = "idx_discharge_requested_at", columnList = "requested_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"patient", "registration", "hospital", "nurse", "doctor", "nurseAssignment", "doctorAssignment"})
public class DischargeApproval extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_registration"))
    private PatientHospitalRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nurse_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_nurse"))
    private Staff nurse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_staff_id",
        foreignKey = @ForeignKey(name = "fk_discharge_doctor"))
    private Staff doctor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nurse_assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_nurse_assignment"))
    private UserRoleHospitalAssignment nurseAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_assignment_id",
        foreignKey = @ForeignKey(name = "fk_discharge_doctor_assignment"))
    private UserRoleHospitalAssignment doctorAssignment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private DischargeStatus status = DischargeStatus.PENDING;

    @Column(name = "nurse_summary", length = 4000)
    private String nurseSummary;

    @Column(name = "doctor_note", length = 4000)
    private String doctorNote;

    @Column(name = "rejection_reason", length = 4000)
    private String rejectionReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Version
    private Long version;

    @PrePersist
    private void beforeSave() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = DischargeStatus.PENDING;
        }
    }
}
