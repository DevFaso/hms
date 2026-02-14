package com.example.hms.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(
    name = "roles",
    schema = "\"security\"",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_name", columnNames = {"name"}),
        @UniqueConstraint(name = "uq_role_code", columnNames = {"code"})
    },
    indexes = {
        @Index(name = "idx_role_name", columnList = "name"),
        @Index(name = "idx_role_code", columnList = "code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"userRoles", "permissions"})
public class Role extends BaseEntity {

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, unique = true, length = 50)
    private String name; // e.g. "HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"

    @NotBlank
    @Size(max = 50)
    @Column(name = "code", unique = true, nullable = false, length = 50)
    private String code; // stable programmatic code, can mirror "ROLE_*"

    @Size(max = 255)
    @Column(length = 255)
    private String description;

    /** Keep sets non-null with @Builder.Default so Lombok builder won't wipe them. */
    @Builder.Default
    @OneToMany(
        mappedBy = "role",
        fetch = FetchType.LAZY,
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        orphanRemoval = false
    )
    private Set<UserRole> userRoles = new HashSet<>();

    /** ManyToMany typically should NOT cascade REMOVE; let the join table handle linkage. */
    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
        schema = "\"security\"",
        joinColumns = @JoinColumn(name = "role_id", foreignKey = @ForeignKey(name = "fk_role_permissions_role")),
        inverseJoinColumns = @JoinColumn(name = "permission_id", foreignKey = @ForeignKey(name = "fk_role_permissions_permission"))
    )
    private Set<Permission> permissions = new HashSet<>();

    /** Helpers keep bidirectional links in sync and guard against nulls/dupes. */
    public void addPermission(Permission permission) {
        if (permission == null) return;
        if (this.permissions.add(permission)) {
            permission.getRoles().add(this);
        }
    }

    public void removePermission(Permission permission) {
        if (permission == null) return;
        if (this.permissions.remove(permission)) {
            permission.getRoles().remove(this);
        }
    }

    @Transient
    public Set<User> getUsers() {
        if (userRoles == null || userRoles.isEmpty()) return Set.of();
        return userRoles.stream()
            .map(UserRole::getUser)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }

    @PrePersist
    @PreUpdate
    void normalize() {
        if (name != null) name = name.trim();
        if (code != null) code = code.trim().toUpperCase();
        if (description != null) description = description.trim();
    }
}
