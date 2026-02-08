package com.example.hms.model;

import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

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
