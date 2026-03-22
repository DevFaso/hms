package com.example.hms.model;

import com.example.hms.enums.HealthMaintenanceReminderStatus;
import com.example.hms.enums.HealthMaintenanceReminderType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "health_maintenance_reminders",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_hmr_patient", columnList = "patient_id"),
        @Index(name = "idx_hmr_hospital", columnList = "hospital_id"),
        @Index(name = "idx_hmr_due_date", columnList = "due_date"),
        @Index(name = "idx_hmr_status", columnList = "status")
    }
)
@EqualsAndHashCode(callSuper = true)
@ToString
public class HealthMaintenanceReminder extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private HealthMaintenanceReminderType type;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private HealthMaintenanceReminderStatus status = HealthMaintenanceReminderStatus.PENDING;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "completed_by", length = 200)
    private String completedBy;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void updateOverdueStatus() {
        if (status == HealthMaintenanceReminderStatus.PENDING && dueDate != null
                && LocalDate.now().isAfter(dueDate)) {
            status = HealthMaintenanceReminderStatus.OVERDUE;
        }
    }
}
