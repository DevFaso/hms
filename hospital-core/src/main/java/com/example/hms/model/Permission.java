package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.util.Set;

@Entity
@Table(
    name = "permissions",
    schema = "\"security\"",
    uniqueConstraints = {
        // Prefer uniqueness on CODE within an assignment (hospital scope)
        @UniqueConstraint(name = "uq_permission_assignment_code", columnNames = {"assignment_id", "code"})
    },
    indexes = {
        @Index(name = "idx_perm_assignment", columnList = "assignment_id"),
        @Index(name = "idx_perm_code", columnList = "code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"roles"})
@EqualsAndHashCode(callSuper = true)
public class Permission extends BaseEntity {

    /** Human-readable label (e.g., “Read Patients”). */
    @NotBlank
    @Size(max = 50)
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** Canonical code (e.g., PATIENT_READ). */
    @NotBlank
    @Size(max = 80)
    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @ManyToMany(mappedBy = "permissions", fetch = FetchType.LAZY)
    private Set<Role> roles;

    /** Scope (role@hospital). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_permission_assignment"))
    private UserRoleHospitalAssignment assignment;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (name != null) name = name.trim();
        if (code != null) code = code.trim().toUpperCase();
    }
}
