package com.example.hms.model;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.enums.StaffLeaveType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(
    name = "staff_leave_requests",
    schema = "hospital",
    indexes = {
        @Index(name = "idx_staff_leave_staff", columnList = "staff_id"),
        @Index(name = "idx_staff_leave_hospital", columnList = "hospital_id"),
        @Index(name = "idx_staff_leave_department", columnList = "department_id"),
        @Index(name = "idx_staff_leave_status", columnList = "status"),
        @Index(name = "idx_staff_leave_start_date", columnList = "start_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class StaffLeaveRequest extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_leave_staff"))
    private Staff staff;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_leave_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_staff_leave_department"))
    private Department department;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 32)
    private StaffLeaveType leaveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private StaffLeaveStatus status = StaffLeaveStatus.PENDING;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "manager_note", length = 1000)
    private String managerNote;

    @Builder.Default
    @Column(name = "requires_coverage", nullable = false)
    private boolean requiresCoverage = false;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_leave_requested_by"))
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id",
        foreignKey = @ForeignKey(name = "fk_staff_leave_reviewed_by"))
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (startDate == null || endDate == null) {
            throw new IllegalStateException("Leave request must have start and end dates");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalStateException("Leave end date cannot be before start date");
        }
        if ((startTime != null && endTime == null) || (startTime == null && endTime != null)) {
            throw new IllegalStateException("Leave start and end time must both be provided or omitted");
        }
        if (startTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalStateException("Leave end time must be after start time");
        }
        if (staff != null && hospital != null && staff.getHospital() != null
            && !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Leave hospital must match staff hospital");
        }
        if (department != null && hospital != null && department.getHospital() != null
            && !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Leave department must belong to the same hospital");
        }
    }
}
