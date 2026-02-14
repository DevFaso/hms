package com.example.hms.model;

import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.enums.StaffShiftType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(
    name = "staff_shifts",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_staff_shift_unique_slot",
            columnNames = {"staff_id", "shift_date", "start_time", "end_time"}
        )
    },
    indexes = {
        @Index(name = "idx_staff_shift_staff", columnList = "staff_id"),
        @Index(name = "idx_staff_shift_hospital", columnList = "hospital_id"),
        @Index(name = "idx_staff_shift_department", columnList = "department_id"),
        @Index(name = "idx_staff_shift_date", columnList = "shift_date"),
        @Index(name = "idx_staff_shift_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class StaffShift extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_shift_staff"))
    private Staff staff;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_shift_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_staff_shift_department"))
    private Department department;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false, length = 32)
    @Builder.Default
    private StaffShiftType shiftType = StaffShiftType.FLEX;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private StaffShiftStatus status = StaffShiftStatus.SCHEDULED;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "scheduled_by_user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_shift_scheduled_by"))
    private User scheduledBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_modified_by_user_id",
        foreignKey = @ForeignKey(name = "fk_staff_shift_last_modified_by"))
    private User lastModifiedBy;

    @Builder.Default
    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (shiftDate == null) {
            throw new IllegalStateException("Shift date is required");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalStateException("Shift start and end time are required");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalStateException("Shift end time must be after start time");
        }
        if (staff != null && hospital != null && staff.getHospital() != null
            && !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Shift hospital must match staff hospital");
        }
        if (department != null && hospital != null && department.getHospital() != null
            && !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Shift department must belong to the same hospital");
        }
        if (status == null) {
            status = StaffShiftStatus.SCHEDULED;
        }
        if (shiftType == null) {
            shiftType = StaffShiftType.FLEX;
        }
    }
}
