package com.example.hms.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "users", schema = "\"security\"", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "username" }),
        @UniqueConstraint(columnNames = { "email" }),
        @UniqueConstraint(columnNames = { "phone_number" })
})
@ToString(exclude = { "userRoles", "createdAppointments", "auditEvents", "patientProfile", "staffProfile",
        "mfaEnrollments", "recoveryContacts" })
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    @Column(name = "phone_number", length = 20, nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Builder.Default
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    private LocalDateTime lastLoginAt;

    @Column(name = "activation_token", unique = true, length = 100)
    private String activationToken;

    @Column(name = "activation_token_expires_at")
    private LocalDateTime activationTokenExpiresAt;

    /**
     * Whether the user must reset password on next login (e.g., system-generated
     * credentials).
     */
    @Builder.Default
    @Column(name = "force_password_change", nullable = false, columnDefinition = "boolean default false")
    private boolean forcePasswordChange = false;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "password_rotation_warning_at")
    private LocalDateTime passwordRotationWarningAt;

    @Column(name = "password_rotation_forced_at")
    private LocalDateTime passwordRotationForcedAt;

    /** Explicit user↔role join table */
    @Builder.Default
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserRole> userRoles = new HashSet<>();

    /** Appointments the user created (optional) */
    @Builder.Default
    @OneToMany(mappedBy = "createdBy", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Appointment> createdAppointments = new HashSet<>();

    /** Patient/Staff 1:1 profiles */
    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Patient patientProfile;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Staff staffProfile;

    @Builder.Default
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<AuditEventLog> auditEvents = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserMfaEnrollment> mfaEnrollments = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserRecoveryContact> recoveryContacts = new HashSet<>();

    // Convenience helpers
    public void addRole(Role role) {
        if (role == null)
            return;
        if (getId() == null || role.getId() == null) {
            throw new IllegalStateException("User and Role must be persisted before linking.");
        }
        UserRole link = UserRole.builder()
                .id(new UserRoleId(getId(), role.getId()))
                .user(this)
                .role(role)
                .build();
        this.userRoles.add(link);
    }

    public void removeRole(Role role) {
        if (role == null)
            return;
        UUID roleId = role.getId();
        this.userRoles.removeIf(ur -> ur.getRole() != null && roleId != null
                && roleId.equals(ur.getRole().getId()));
    }

    public Set<UserRole> getUserRoles() {
        return userRoles;
    }

    public void addMfaEnrollment(UserMfaEnrollment enrollment) {
        if (enrollment == null) {
            return;
        }
        enrollment.setUser(this);
        mfaEnrollments.add(enrollment);
    }

    public void addRecoveryContact(UserRecoveryContact contact) {
        if (contact == null) {
            return;
        }
        contact.setUser(this);
        recoveryContacts.add(contact);
    }
}
