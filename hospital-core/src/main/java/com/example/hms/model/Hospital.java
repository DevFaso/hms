package com.example.hms.model;

import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Entity
@Table(
    name = "hospitals",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_hospital_code", columnNames = "code")
    },
    indexes = {
        @Index(name = "idx_hospital_active", columnList = "active"),
        @Index(name = "idx_hospital_city", columnList = "city"),
        @Index(name = "idx_hospital_country", columnList = "country")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Hospital extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @Size(max = 2048)
    @Column(name = "address", length = 2048)
    private String address;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Size(max = 100)
    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    @Size(max = 100) @Column(length = 100) private String city;
    @Size(max = 100) @Column(length = 100) private String state;
    @Size(max = 50)  @Column(length = 50)  private String zipCode;
    @Size(max = 100) @Column(length = 100) private String country;
    @Size(max = 100) @Column(length = 100) private String province;
    @Size(max = 100) @Column(length = 100) private String region;
    @Size(max = 100) @Column(length = 100) private String sector;
    @Size(max = 50)  @Column(length = 50)  private String poBox;
    @Size(max = 30)  @Column(length = 30)  private String phoneNumber;

    @Email
    @Size(max = 255)
    @Column(length = 255)
    private String email;

    @Size(max = 255)
    @Column(length = 255)
    private String website;

    /**
     * DHIS2 organisation-unit UID (11-char DHIS2 convention) bound to this
     * hospital for ADX exports. Null when the facility does not export.
     * The DB-side CHECK enforces this format too; the {@code @Pattern}
     * here surfaces the violation at validation time so callers see a
     * user-actionable error rather than a 500 on commit.
     */
    @Size(max = 11)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 org-unit UID must be 11 chars, alphanumeric, leading letter")
    @Column(name = "dhis2_org_unit_uid", length = 11)
    private String dhis2OrgUnitUid;

    /**
     * URL template for the hospital's PACS viewer (e.g.
     * {@code https://orthanc.local/viewer.html?studyUid={studyInstanceUid}}).
     * Resolved by the imaging-report mapper to surface a "View in PACS" link
     * when only a study UID is available. Null when the hospital has no PACS.
     */
    @Size(max = 500)
    @Column(name = "pacs_viewer_url_template", length = 500)
    private String pacsViewerUrlTemplate;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    // ── Hospital lifecycle (MVP-c batch) ─────────────────────────────
    // Mirrors Organization.lifecycleState. `active` continues to gate
    // UI / list filters; `lifecycleState` drives login-time blocking
    // and is the source of truth for purge scheduling at the hospital
    // level. Suspending a hospital narrows the org-wide block to a
    // single facility.
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 32)
    private HospitalLifecycleState lifecycleState = HospitalLifecycleState.ACTIVE;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_by")
    private UUID suspendedBy;

    @Column(name = "suspension_reason", length = 1000)
    private String suspensionReason;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by")
    private UUID archivedBy;

    @Column(name = "archive_reason", length = 1000)
    private String archiveReason;

    @Column(name = "purge_scheduled_for")
    private Instant purgeScheduledFor;

    @Column(name = "purge_scheduled_by")
    private UUID purgeScheduledBy;

    @Column(name = "purge_reason", length = 1000)
    private String purgeReason;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Department> departments = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY)
    private Set<Treatment> hospitalServices = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY)
    private Set<Appointment> appointments = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY)
    private Set<Encounter> encounters = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY)
    private Set<BillingInvoice> billingInvoices = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PatientHospitalRegistration> patientRegistrations = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY)
    private Set<UserRoleHospitalAssignment> userRoleHospitalAssignments = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id",
        foreignKey = @ForeignKey(name = "fk_hospital_organization"))
    private Organization organization;

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "hospital_owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "hospital_owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "hospital_data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "hospital_service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();

    @Embedded
    @AttributeOverride(name = "ehrSystem", column = @Column(name = "hospital_ehr_system", length = 120))
    @AttributeOverride(name = "billingSystem", column = @Column(name = "hospital_billing_system", length = 120))
    @AttributeOverride(name = "inventorySystem", column = @Column(name = "hospital_inventory_system", length = 120))
    @AttributeOverride(name = "integrationNotes", column = @Column(name = "hospital_integration_notes", length = 255))
    @Builder.Default
    private PlatformServiceMetadata platformServiceMetadata = PlatformServiceMetadata.empty();

    @Builder.Default
    @OneToMany(mappedBy = "hospital", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<HospitalPlatformServiceLink> platformServiceLinks = new HashSet<>();

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (code != null) code = code.trim().toUpperCase();
        if (email != null) email = email.trim().toLowerCase();
        if (name != null) name = name.trim();
        if (website != null) website = website.trim();
        if (city != null) city = city.trim();
        if (country != null) country = country.trim();
    }

    public void addDepartment(Department d) {
        if (d == null) return;
        departments.add(d);
        d.setHospital(this);
    }
    public void removeDepartment(Department d) {
        if (d == null) return;
        departments.remove(d);
        if (d.getHospital() == this) d.setHospital(null);
    }

    public void addPlatformServiceLink(HospitalPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        link.setHospital(this);
        platformServiceLinks.add(link);
    }

    public void removePlatformServiceLink(HospitalPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        boolean removed = platformServiceLinks.removeIf(existing ->
            existing == link || Objects.equals(existing.getId(), link.getId()));
        if (removed) {
            link.setHospital(null);
        }
    }

    @Override
    public String toString() {
        return "Hospital{" +
            "id=" + getId() +
            ", name='" + name + '\'' +
            ", code='" + code + '\'' +
            ", city='" + city + '\'' +
            ", country='" + country + '\'' +
            ", active=" + active +
            '}';
    }
}
