package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_role_hospital_assignment",
    schema = "\"security\"",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "hospital_id", "role_id"})
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"user", "hospital", "role", "registeredBy"})
@EqualsAndHashCode(callSuper = true)
public class UserRoleHospitalAssignment extends BaseEntity {

    @Column(name = "assignment_code", unique = true, length = 50)
    private String assignmentCode;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_user_id",
        foreignKey = @ForeignKey(name = "fk_user_role_assignment_registered_by"))
    private User registeredBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_user_role_assignment_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_user_role_assignment_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_user_role_assignment_role"))
    private Role role;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "confirmation_code", length = 16)
    private String confirmationCode;

    @Column(name = "confirmation_sent_at")
    private LocalDateTime confirmationSentAt;

    @Column(name = "confirmation_verified_at")
    private LocalDateTime confirmationVerifiedAt;

    @PrePersist
    protected void onAssign() {
        if (assignedAt == null) {
            assignedAt = LocalDateTime.now();
        }
        validateBusinessRules();
    }

    @PreUpdate
    protected void onUpdateAssign() {
        validateBusinessRules();
    }

    private void validateBusinessRules() {
        if (role == null) return;

        // Prefer role code; fall back to name
        String raw = (role.getCode() != null && !role.getCode().isBlank())
            ? role.getCode()
            : role.getName();
        String roleKey = raw == null ? "" : raw.trim().toUpperCase();

        if (Boolean.TRUE.equals(active)) {
            // 1) Patients cannot have ACTIVE hospital assignments
            if ("ROLE_PATIENT".equals(roleKey) || "PATIENT".equals(roleKey)) {
                throw new IllegalStateException("Patients cannot have active hospital assignments.");
            }
            // 2) SUPER_ADMIN must be global (no hospital)
            if (("ROLE_SUPER_ADMIN".equals(roleKey) || "SYSTEM_ADMIN".equals(roleKey))
                    && hospital != null) {
                throw new IllegalStateException("Super Admins must not be assigned to a hospital (global only).");
            }
        }
    }
}
