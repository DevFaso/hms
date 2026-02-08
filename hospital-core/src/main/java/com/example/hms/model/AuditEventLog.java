package com.example.hms.model;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
    name = "audit_event_logs",
    schema = "support",
    indexes = {
        @Index(name = "idx_audit_user_time", columnList = "user_id, event_timestamp"),
        @Index(name = "idx_audit_assignment", columnList = "assignment_id"),
        @Index(name = "idx_audit_type_time", columnList = "event_type, event_timestamp"),
        @Index(name = "idx_audit_entity", columnList = "target_entity_type, target_resource_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "assignment"})
public class AuditEventLog extends BaseEntity {
    @Size(max = 255)
    @Column(name = "role_name", length = 255)
    private String roleName;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_audit_user"))
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assignment_id",
        foreignKey = @ForeignKey(name = "fk_audit_assignment"))
    private UserRoleHospitalAssignment assignment;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuditEventType eventType;

    @NotBlank
    @Size(max = 2048)
    @Column(name = "event_description", nullable = false, length = 2048)
    private String eventDescription;

    /** When the event happened (separate from BaseEntity.createdAt if you ever ingest past events). */
    @CreationTimestamp
    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private LocalDateTime eventTimestamp;

    @Size(max = 45)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AuditStatus status;

    /** Denormalized snapshot fields for quick reads (optional). */
    @Size(max = 255)
    @Column(name = "user_name", length = 255)
    private String userName;

    @Size(max = 255)
    @Column(name = "hospital_name", length = 255)
    private String hospitalName;

    /** Use regular text column for details to avoid LOB stream errors. */
    @Column(name = "details", length = 2048)
    private String details;

    /** External/resource identifier (UUID-as-text or other). */
    @Size(max = 100)
    @Column(name = "target_resource_id", length = 100)
    private String resourceId;

    @Size(max = 50)
    @Column(name = "target_entity_type", length = 50)
    private String entityType;
    
        @Size(max = 255)
        @Column(name = "resource_name", length = 255)
        private String resourceName;

    @PrePersist
    @PreUpdate
    private void validateAndSnapshot() {
        if (assignment != null && assignment.getHospital() != null) {
            // Optional consistency: if assignment has a user, ensure it matches the acting user
            if (assignment.getUser() != null && user != null
                && !Objects.equals(assignment.getUser().getId(), user.getId())) {
                throw new IllegalStateException("Audit assignment.user must match audit user");
            }
            if (hospitalName == null) {
                hospitalName = assignment.getHospital().getName();
            }
        }
        if (user != null && userName == null) {
            String f = user.getFirstName() != null ? user.getFirstName().trim() : "";
            String l = user.getLastName() != null ? user.getLastName().trim() : "";
            String full = (f + " " + l).trim();
            userName = full.isEmpty() ? user.getEmail() : full;
        }
    }
}
