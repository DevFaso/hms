package com.example.hms.model;

import com.example.hms.enums.AppointmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(
    name = "appointments",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_appt_staff_slot",
            columnNames = {"staff_id", "appointment_date", "start_time", "end_time"})
    },
    indexes = {
        @Index(name = "idx_appt_patient", columnList = "patient_id"),
        @Index(name = "idx_appt_staff", columnList = "staff_id"),
        @Index(name = "idx_appt_hospital", columnList = "hospital_id"),
        @Index(name = "idx_appt_date", columnList = "appointment_date"),
        @Index(name = "idx_appt_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "staff", "hospital", "createdBy", "assignment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Appointment extends BaseEntity {
    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointment_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointment_staff"))
    private Staff staff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointment_hospital"))
    private Hospital hospital;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointment_department"))
    private Department department;

    @NotNull
    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @NotNull
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AppointmentStatus status;

    @Column(name = "reason", length = 2048)
    private String reason;

    @Column(name = "notes", length = 2048)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by",
        foreignKey = @ForeignKey(name = "fk_appointment_created_by"))
    private User createdBy;

    /** Context (role@hospital) used to create/own this appointment. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointment_assignment"))
    private UserRoleHospitalAssignment assignment;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalStateException("Appointment end_time must be after start_time");
        }
        // Staff must belong to the selected hospital
        if (staff == null || staff.getHospital() == null || hospital == null
            || !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Staff does not belong to selected hospital");
        }
        // Assignment must be hospital-scoped and match appointment hospital
        if (assignment == null || assignment.getHospital() == null
            || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Appointment assignment.hospital must match hospital");
        }
        if (status == null) status = AppointmentStatus.SCHEDULED;
    }
}
