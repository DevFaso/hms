package com.example.hms.model;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
    name = "staff",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_staff_user", columnNames = {"user_id"}),
        @UniqueConstraint(name = "uq_staff_license_user", columnNames = {"license_number", "user_id"}),
        @UniqueConstraint(name = "uq_staff_user_hospital", columnNames = {"user_id", "hospital_id"})
    },
    indexes = {
        @Index(name = "idx_staff_hospital", columnList = "hospital_id"),
        @Index(name = "idx_staff_department", columnList = "department_id"),
        @Index(name = "idx_staff_assignment", columnList = "assignment_id"),
        @Index(name = "idx_staff_license", columnList = "license_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "hospital", "department", "assignment", "availabilities"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Staff extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
        foreignKey = @ForeignKey(name = "fk_staff_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_staff_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_staff_assignment"))
    private UserRoleHospitalAssignment assignment;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_title", nullable = false, length = 48)
    private JobTitle jobTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 24)
    private EmploymentType employmentType;

    @Column(length = 100)
    private String specialization;

    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    @Column(name = "npi", length = 20, unique = true)
    private String npi;

    @Column(name = "signature_certificate_id", length = 120)
    private String signatureCertificateId;

    @Column(name = "signature_public_key", columnDefinition = "TEXT")
    private String signaturePublicKey;

    private LocalDateTime signatureCapturedAt;

    private LocalDateTime signatureRevokedAt;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(length = 500)
    private String name;

    @Builder.Default
    @OneToMany(mappedBy = "staff", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<StaffAvailability> availabilities = new HashSet<>();

    @PrePersist
    @PreUpdate
    private void beforeSave() {
        // default for new records
        if (employmentType == null) {
            employmentType = EmploymentType.FULL_TIME;
        }
        // integrity checks
        if (assignment == null || assignment.getHospital() == null) {
            throw new IllegalStateException("Staff must have a hospital-scoped assignment");
        }
        if (hospital == null || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Staff.assignment.hospital must match staff.hospital");
        }
        if (npi != null) {
            npi = normalizeIdentifier(npi, 20);
            if (npi != null && !npi.matches("\\d{10}")) {
                throw new IllegalStateException("Staff NPI must be a 10-digit numeric identifier");
            }
        }
        signatureCertificateId = normalizeIdentifier(signatureCertificateId, 120);
        if (signaturePublicKey != null && signaturePublicKey.isBlank()) {
            signaturePublicKey = null;
        }
    }

    private String normalizeIdentifier(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    // Helper: full name from User, null-safe
    public String getFullName() {
        if (user == null) return null;
        String f = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String l = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }

    // Sensible default when jobTitle is unset
    public JobTitle getJobTitle() {
        return jobTitle != null ? jobTitle : JobTitle.ADMINISTRATIVE_STAFF;
    }

    /** Department-head eligibility based on role CODEs. */
    public boolean isEligibleForDepartmentHead() {
        if (assignment == null || assignment.getRole() == null) return false;
        String code = assignment.getRole().getCode();
        return "ROLE_DOCTOR".equalsIgnoreCase(code)
            || "ROLE_HOSPITAL_ADMIN".equalsIgnoreCase(code)
            || "ROLE_SUPER_ADMIN".equalsIgnoreCase(code)
            || "ROLE_NURSE".equalsIgnoreCase(code)
            || "ROLE_PHARMACIST".equalsIgnoreCase(code)
            || "ROLE_RADIOLOGIST".equalsIgnoreCase(code);
    }
}
